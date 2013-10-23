package com.wattu.sidpi;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public class ClientContext {
	
	private SIDPiController sid;
	/** String encoding */
	private static final Charset ISO_8859 = Charset.forName("ISO-8859-1");

	/** See class comment for definition of version 2. */
	private static final byte SID_NETWORK_PROTOCOL_VERSION = 2;	

	/** Maximum time to wait for queue in milliseconds. */
	private static final long MAX_TIME_TO_WAIT_FOR_QUEUE = 20;	
	
	private final SIDRunnerThread eventConsumerThread; 
	
	/** Available commands to clients */
	private enum Command {
		/* 0 */
		FLUSH,
		TRY_SET_SID_COUNT,
		MUTE,
		TRY_RESET,

		/* 4 */
		TRY_DELAY,
		TRY_WRITE,
		TRY_READ,
		GET_VERSION,
		
		/* 8 */
		TRY_SET_SAMPLING,
		TRY_SET_CLOCKING,
		GET_CONFIG_COUNT,
		GET_CONFIG_INFO,
		
		/* 12 */
		SET_SID_POSITION,
		SET_SID_LEVEL,
		TRY_SET_SID_MODEL,
	}

	/** Responses generated by server. */
	private enum Response {
		OK,
		BUSY,
		ERR,
		READ,
		VERSION,
		COUNT,
		INFO,
	}

	/** Expected buffer fill rate */
	private final int latency;
	
	/** Cached commands because values() returns new array each time. */
	private final Command[] commands = Command.values();

	/** Our backend thread */
	//private final AudioGeneratorThread eventConsumerThread;

	/** Shadow SID clocked with client to read from */
	//private SID[] sidRead;
	
	/** Allocate read buffer. Maximum command + maximum socket buffer size (assumed to be per request 16K) */
	private final ByteBuffer dataRead = ByteBuffer.allocateDirect(65536 + 4 + 16384);

	/** Allocate write buffer. Maximum supported writes are currently 260 bytes long. */
	private final ByteBuffer dataWrite = ByteBuffer.allocateDirect(260);
	
	/** Current command. */
	private Command command;
	
	/** Length of data packet associated to command. */
	private int dataLength;
	
	/** Current clock value in input. */
	private long inputClock;

	/** Map which holds all instances of each client connection. */
	private static Map<SocketChannel, ClientContext> clientContextMap = new ConcurrentHashMap<SocketChannel, ClientContext>();
	
	private ClientContext(SIDPiController sid) {
		this.sid = sid;
		this.latency = 100000;
		dataWrite.position(dataWrite.capacity());
		eventConsumerThread = new SIDRunnerThread(sid);
		eventConsumerThread.start();
		dataRead.limit(4);
		inputClock = eventConsumerThread.getPlaybackClock();
	}
	
	/** Callback to handle protocol after new data has been received. 
	 * @throws InvalidCommandException */
	private void processReadBuffer() throws Exception {
		/* Not enough data to handle the data packet? */
		if (dataRead.position() < dataRead.limit()) {
			return;
		}
	
		/* Needs to read command? */
		if (command == null) {
			int commandByte = dataRead.get(0) & 0xff;
			if (commandByte >= commands.length) {
				throw new RuntimeException("Unknown command number: " + commandByte);
			}
			command = commands[commandByte];	
			//System.out.println(command);
			int sidNumber = dataRead.get(1) & 0xff;
			dataLength = dataRead.getShort(2) & 0xffff;

			dataRead.limit(4 + dataLength);
			if (dataRead.position() < dataRead.limit()) {
				return;
			}
		}
		
		long clientTimeDifference = inputClock - eventConsumerThread.getPlaybackClock();
		boolean isBufferFull = clientTimeDifference > latency;
		boolean isBufferHalfFull = clientTimeDifference > latency / 2;
		
		/* Handle data packet. */
		final BlockingQueue<SIDWrite> sidCommandQueue = eventConsumerThread.getSidCommandQueue();

		dataWrite.clear();
		
		switch (command) {
		case FLUSH:
			if (dataLength != 0) {
				throw new RuntimeException("FLUSH needs no data" + dataLength);
			}
			
			sidCommandQueue.clear();
			/* The playbackclock may still increase for a while, because
			 * audio generation may still be ongoing. We aren't allowed
			 * to wait for it, either, so this is the best we can do. */
			inputClock = eventConsumerThread.getPlaybackClock();
			dataWrite.put((byte) Response.OK.ordinal());
			break;

		case TRY_SET_SID_COUNT:
			dataWrite.put((byte) Response.OK.ordinal());
			break;
			
		case MUTE:
			dataWrite.put((byte) Response.OK.ordinal());
			break;

		case TRY_RESET:
			sid.reset();
			dataWrite.put((byte) Response.OK.ordinal());
			break;

		/* SID command queuing section. */
		case TRY_DELAY: {
			if (isBufferHalfFull) {
				eventConsumerThread.ensureDraining();
			}
			
			if (isBufferFull) {
				dataWrite.put((byte) Response.BUSY.ordinal());
				break;
			}
			final int cycles = dataRead.getShort(4) & 0xffff;
			
			handleDelayPacket(0,cycles);
			dataWrite.put((byte) Response.OK.ordinal());
			break;
		}

		case TRY_WRITE: {
			if (dataLength < 4 && (dataLength % 4) != 0) {
				throw new RuntimeException("TRY_WRITE needs 4*n bytes, with n > 1 (hardsid protocol)");
			}
			if (isBufferHalfFull) {
				eventConsumerThread.ensureDraining();
			}
			
			if (isBufferFull) {
				dataWrite.put((byte) Response.BUSY.ordinal());
				break;
			}
			handleWritePacket(dataLength);
			dataWrite.put((byte) Response.OK.ordinal());
			break;
		}

		case TRY_READ: {
			if ((dataLength - 3) % 4 != 0) {
				throw new RuntimeException("READ needs 4*n+3 bytes (4*n hardsid protocol + 16-bit delay + register to read)");
			}

			System.out.println("Tried to read");
			/* Handle the read off our simulated SID device. */
			final int readCycles = dataRead.getShort(4 + dataLength - 3) & 0xffff;
			final byte register = dataRead.get(4 + dataLength - 1);
			
			dataWrite.put((byte) Response.READ.ordinal());
			dataWrite.put((byte) sid.readRegister(register));
			break;
		}

		/* Metdata method section */
		case GET_VERSION:
			if (dataLength != 0) {
				throw new RuntimeException("GET_VERSION needs no data");
			}
			
			dataWrite.put((byte) Response.VERSION.ordinal());
			dataWrite.put(SID_NETWORK_PROTOCOL_VERSION);
			break;
			
		case TRY_SET_SAMPLING:
			dataWrite.put((byte) Response.OK.ordinal());
			break;
			
		case TRY_SET_CLOCKING:
			dataWrite.put((byte) Response.OK.ordinal());
			break;
			
		case GET_CONFIG_COUNT:
			if (dataLength != 0) {
				throw new RuntimeException("GET_COUNT needs no data");
			}

			dataWrite.put((byte) Response.COUNT.ordinal());
			dataWrite.put((byte) 1);
			break;
			
		case GET_CONFIG_INFO:
			if (dataLength != 0) {
				throw new RuntimeException("GET_INFO needs no data");
			}

			dataWrite.put((byte) Response.INFO.ordinal());
			dataWrite.put((byte) (0));
			byte[] name = "SidPi".getBytes(ISO_8859);

			dataWrite.put(name, 0, Math.min(name.length, 255));
			dataWrite.put((byte) 0);
			break;
			
		case SET_SID_POSITION:
			if (dataLength != 1) {
				throw new RuntimeException("SET_SID_POSITION needs 1 byte");
			}
			
			dataWrite.put((byte) Response.OK.ordinal());
			break;

		case SET_SID_LEVEL:
			if (dataLength != 1) {
				throw new RuntimeException("SET_SID_LEVEL needs 1 byte");
			}

			dataWrite.put((byte) Response.OK.ordinal());
			break;

		case TRY_SET_SID_MODEL:
			if (dataLength != 1) {
				throw new RuntimeException("SET_SID_LEVEL needs 1 byte");
			}
			
			dataWrite.put((byte) Response.OK.ordinal());
			break;
			
		default:
			throw new RuntimeException("Unsupported command: " + command);
		}
		
		dataWrite.limit(dataWrite.position());
		dataWrite.rewind();

		/* Move rest of the junk to beginning of bytebuffer */
		dataRead.position(4 + dataLength);
		dataRead.compact();
		
		/* Mark that we need to read a new command. */
		command = null;
		dataRead.limit(4);
	}		

	private ByteBuffer getReadBuffer() {
		return dataRead;
	}

	private ByteBuffer getWriteBuffer() {
		return dataWrite;
	}
		
	public static void listenForClients(SIDPiController sid) {
		/* check for new connections. */
		ServerSocketChannel ssc = null;
		try {
			Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
			ssc = ServerSocketChannel.open();
			ssc.configureBlocking(false);
			System.out.println("Opening listening socket.");
			ssc.socket().bind(new InetSocketAddress("192.168.1.66", 6581));

			Selector s = Selector.open();
			ssc.register(s, SelectionKey.OP_ACCEPT);
	
			clientContextMap.clear();
			
			
			while (s.select() > 0) {
				for (SelectionKey sk : s.selectedKeys()) {
					if (sk.isAcceptable()) {
						SocketChannel sc = ((ServerSocketChannel) sk.channel()).accept();
						sc.socket().setReceiveBufferSize(16384);
						sc.socket().setSendBufferSize(1024);
						sc.configureBlocking(false);
	
						sc.register(s, SelectionKey.OP_READ);
						ClientContext cc = new ClientContext(sid);
						clientContextMap.put(sc, cc);
						System.out.println("New client: " + cc);
					}
					
					if (sk.isReadable()) {
						SocketChannel sc = (SocketChannel) sk.channel();
						ClientContext cc = clientContextMap.get(sc);
						
						try {
							int length = sc.read(cc.getReadBuffer());
							if (length == -1) {
								throw new EOFException();
							}
							
							cc.processReadBuffer();
						}
						catch (Exception e) {
							System.out.println("Read: closing client " + cc + " due to exception: " + e);
	
							cc.dispose();
							clientContextMap.remove(sc);
							sk.cancel();
							sc.close();
							
							/* IOExceptions are probably not worth bothering user about, they could be normal
							 * stuff like apps exiting or closing connection. Other stuff is important, though.
							 */
							if (! (e instanceof IOException)) {
								System.out.println(e);
							}
							continue;
						}
	
						/* Switch to writing? */
						ByteBuffer data = cc.getWriteBuffer();
						if (data.remaining() != 0) {
							sc.register(s, SelectionKey.OP_WRITE);
						}
					}
					
					if (sk.isWritable()) {
						SocketChannel sc = (SocketChannel) sk.channel();
						ClientContext cc = clientContextMap.get(sc);
	
						try {
							ByteBuffer data = cc.getWriteBuffer();
							sc.write(data);
	
							/* Switch to reading? */
							if (data.remaining() == 0) {
								sc.register(s, SelectionKey.OP_READ);
							}
						}
						catch (IOException ioe) {
							System.out.println("Write: closing client " + cc + " due to exception: " + ioe);
							cc.dispose();
							clientContextMap.remove(sc);
							sk.cancel();
							sc.close();
							continue;
						}
					}
				}
				
				s.selectedKeys().clear();
			}
	
			for (ClientContext cc : clientContextMap.values()) {
				System.out.println("Cleaning up client: " + cc);
				cc.dispose();
			}
			for (SocketChannel sc : clientContextMap.keySet()) {
				sc.close();
			}
			for (ClientContext cc : clientContextMap.values()) {
				cc.disposeWait();
			}
			
			ssc.close();
			System.out.println("Listening socket closed.");
			
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	protected void dispose() {
		//eventConsumerThread.getSidCommandQueue().add(SIDWrite.makeEnd());
		//eventConsumerThread.ensureDraining();
	}
	
	protected void disposeWait() {
		
	}
	private void handleWritePacket(int dataLength) {
		Queue<SIDWrite> q = eventConsumerThread.getSidCommandQueue();
		for (int i = 0; i < dataLength; i += 4) {
			int writeCycles = dataRead.getShort(4 + i) & 0xffff;
			byte reg = dataRead.get(4 + i + 2);
			byte sid = (byte) ((reg & 0xe0) >> 5);
			reg &= 0x1f;
			final byte value = dataRead.get(4 + i + 3);
			inputClock += writeCycles;
			SIDWrite write = new SIDWrite(reg,value,writeCycles);
			q.add(write);
		}
	}
	private void handleDelayPacket(int sidNumber, int cycles)  {
		Queue<SIDWrite> q = eventConsumerThread.getSidCommandQueue();
		inputClock += cycles;
		SIDWrite write = new SIDWrite(cycles);
		q.add(write);
	}
}