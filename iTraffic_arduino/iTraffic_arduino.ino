#include <Wire.h>

const int LDR_PIN = A0;
int ldrValue = 0;

void setup() {
  Wire.begin(0x08); // 슬레이브 주소
  Wire.onRequest(requestEvent);
}

void loop() {
  ldrValue = analogRead(LDR_PIN);
  delay(500);
}

void requestEvent() {
  Wire.write((ldrValue >> 8) & 0xFF);
  Wire.write(ldrValue & 0xFF);
}
