#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <stdint.h>
#include <errno.h>
#include <string.h>
#include <fcntl.h>
#include <sys/mman.h>
#include "rpi.h"

#define DEFAULT_SID_SPEED_HZ 1000000

#define SER 	2
#define SCLK 	3
#define RCLK 	17
#define CS 		27

uint8_t reset = 1;

void setup_io();

void setup_sid();

void write_bit(uint8_t);

void write_sid(uint8_t,uint8_t);

void reset_sid();

void delay();

void set_output(uint8_t);

void set_output(uint8_t pin) {
	INP_GPIO(pin)
	OUT_GPIO(pin)
} // set_output

void write_bit(uint8_t bit) {
	if(bit > 0) {
		GPIO_SET = 1 << SER;
	} else {
		GPIO_CLR = 1 << SER;
	}
	GPIO_SET = 1 << SCLK;
	delay();
	GPIO_CLR = 1 << SCLK;
} // write_bit

void setup_sid() {
	mmap_devices();

	set_output(SER);
	set_output(RCLK);
	set_output(SCLK);
	set_output(CS);

	start_sid_clock(DEFAULT_FREQ);

	reset_sid();
} // setup_sid

void reset_sid() {
	reset = 0;
	write_sid(0,0);
	reset = 1;
} // reset_sid

void write_sid(uint8_t addr,uint8_t data) {

	int i;
	data %= 0xff;
	addr %= 0x1f;

	GPIO_CLR = 1 << RCLK;

	for(i = 7;i >= 0;i--) {
		write_bit(data >> i);
	}

	write_bit(0); // NC
	write_bit(0); // NC

	write_bit(reset);

	for(i = 4;i >= 0;i--) {
		write_bit(addr >> i);
	}

	GPIO_SET = 1 << RCLK;
	delay();
	GPIO_CLR = 1 << CS;
	delay();
	GPIO_SET = 1 << CS;
	GPIO_CLR = 1 << RCLK;


} // write_sid

void delay() {

} // delay

void mmap_devices() {
	if (map_peripheral(&gpio) == -1) {
		printf(
				"Failed to map the physical GPIO registers into the virtual memory space.\n");
		return;
	}
	if (map_peripheral(&gpio_clock) == -1) {
		printf(
				"Failed to map the physical Clock into the virtual memory space.\n");
		return;
	}
	if (map_peripheral(&gpio_timer) == -1) {
		printf(
				"Failed to map the physical Timer into the virtual memory space.\n");
		return;
	}
} // mmap_devices
void start_sid_clock(int freq) {
	int divi, divr, divf;

	divi = 19200000 / freq;
	divr = 19200000 % freq;
	divf = (int) ((double) divr * 4096.0 / 19200000.0);

	if (divi > 4095)
		divi = 4095;

	*(gpio_clock.addr + 28) = BCM_PASSWORD | GPIO_CLOCK_SOURCE;
	while ((*(gpio_clock.addr + 28) & 0x80) != 0)
		;

	*(gpio_clock.addr + 29) = BCM_PASSWORD | (divi << 12) | divf;
	*(gpio_clock.addr + 28) = BCM_PASSWORD | 0x10 | GPIO_CLOCK_SOURCE;

	*(gpio_timer.addr + TIMER_CONTROL) = 0x0000280;
	*(gpio_timer.addr + TIMER_PRE_DIV) = 0x00000F9;
} // start_sid_clock
