/*
 * SidRunnerThread.h
 *
 *  Created on: 12 Jan 2014
 *      Author: jamie
 */

#ifndef SIDRUNNERTHREAD_H_
#define SIDRUNNERTHREAD_H_

#define CS 	7
#define RW 	0
#define RES 0
#define CLK 4

#define BUFFER_SIZE 65536

#define DEFAULT_SID_SPEED_HZ 1000000

const int DATA[]	= {2,3,17,27,22,10,9,11};
const int ADDR[]	= {8,25,24,23,18};

void sidDelay(int cycles);
void sidWrite(int reg,int value,int writeCycles);
void setupSid();
void *sidThread() ;
void delay(int cycles);
void writeSid(int reg,int val);
void startSidClk(int freq);
void mmapRPIDevices();
void generatePinTables();
void setPinsToOutput();
int playbackReady();
void startPlayback();
void stopPlayback();


#endif /* SIDRUNNERTHREAD_H_ */
