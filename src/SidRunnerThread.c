#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <signal.h>
#include <pthread.h>
#include <time.h>
#include "SidRunnerThread.h"
#include "rpi.h"

pthread_t sidThreadHandle;

unsigned char *buffer;
unsigned int bufReadPos,bufWritePos;
unsigned int dataPins[256];
unsigned int addrPins[32];

void setupSid() {

	int i;
	buffer = malloc((size_t) BUFFER_SIZE);
	bufReadPos = 0;
	bufWritePos = 0;

	if(map_peripheral(&gpio) == -1) {
		printf("Failed to map the physical GPIO registers into the virtual memory space.\n");
		return;
	}
	if(map_peripheral(&timer) == -1) {
		printf("Failed to map the physical Timer into the virtual memory space.\n");
		return;
	}

	for(i=0;i<256;i++) {
		dataPins[i] =   (i & 1)         << DATA[i];
		dataPins[i] |= ((i & 2)   >> 1) << DATA[i];
		dataPins[i] |= ((i & 4)   >> 2) << DATA[i];
		dataPins[i] |= ((i & 8)   >> 3) << DATA[i];
		dataPins[i] |= ((i & 16)  >> 4) << DATA[i];
		dataPins[i] |= ((i & 32)  >> 5) << DATA[i];
		dataPins[i] |= ((i & 64)  >> 6) << DATA[i];
		dataPins[i] |= ((i & 128) >> 7) << DATA[i];
	}

	for(i=0;i<256;i++) {
		printf("Set %d = %x\n",i,dataPins[i]);
		printf("Clr %d = %x\n",i,((unsigned int) !dataPins[i]) & dataPins[255]);
	}

	if (pthread_create(&sidThreadHandle, NULL, sidThread, NULL) == -1)
		perror("cannot create thread");
}

void *sidThread() {
	printf("Sid Thread Running...\n");
	while (1) {
		if(bufWritePos > bufReadPos) {
			if(buffer[bufReadPos] != 0xff)
				writeSid(buffer[bufReadPos],buffer[bufReadPos+1]);

			delay(buffer[bufReadPos+2]);

			if(bufReadPos >= BUFFER_SIZE - 3)
				bufReadPos = 0;
			else
				bufReadPos+=3;
		}
	}
}

void sidDelay(int cycles) {
	if(bufWritePos >= BUFFER_SIZE - 3)
			bufWritePos = 0;

	buffer[bufWritePos] = 0xff;
	buffer[bufWritePos + 1] = 0;
	buffer[bufWritePos + 2] = cycles;
}
void sidWrite(int reg,int value,int writeCycles) {
	if(bufWritePos >= BUFFER_SIZE - 3)
		bufWritePos = 0;
	buffer[bufWritePos] = reg;
	buffer[bufWritePos + 1] = value;
	buffer[bufWritePos + 2] = writeCycles;
	bufWritePos +=3;
}
void delay(int cycles) {
	long long int * beforeCycle, *afterCycle, target;
	struct timespec tim;
	target = *(long long int *)((char *)timer.addr + TIMER_OFFSET) + cycles;
	if(cycles < 10) return;
	if(cycles < 100)
		while(*(long long int *)((char *)timer.addr + TIMER_OFFSET) < target);
	else {
		tim.tv_sec = 0;
		tim.tv_nsec = cycles-80;
		nanosleep(&tim,NULL);
	}
	//printf("target %llu : current %llu\n",target,*(long long int *)((char *)timer.addr + TIMER_OFFSET));

}
void writeSid(int reg,int val) {
	//printf("Write reg %x val %x\n",reg,val);
}

