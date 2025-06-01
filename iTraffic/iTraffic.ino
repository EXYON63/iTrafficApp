#include <WiFi.h>
#include <Wire.h>
#include <WebServer.h>

// I2C 설정
#define I2C_SDA 14
#define I2C_SCL 15
#define SLAVE_ADDR 0x08

WebServer server(80);
int ldrValue = -1;

// 핫스팟 설정 (비밀번호 없음 = OPEN 모드)
const char* ssid = "iTraffic_WIFI";

void handleJson() {
  String json = "{";
  json += "\"sensor\":\"LDR\",";
  json += "\"value\":" + String(ldrValue);
  json += "}";
  server.send(200, "application/json", json);
}

void setup() {
  Serial.begin(115200);

  // I2C 시작
  Wire.begin(I2C_SDA, I2C_SCL);

  // WiFi AP(개방형) 시작
  WiFi.softAP(ssid);  // ← 비밀번호 없이 개방형으로 설정
  IPAddress IP = WiFi.softAPIP();
  Serial.println("AP 시작됨. IP 주소: " + IP.toString());

  // 웹서버 라우팅
  server.on("/json", handleJson);
  server.begin();
}

void loop() {
  // I2C 요청으로 LDR 값 수신
  Wire.beginTransmission(SLAVE_ADDR);
  Wire.endTransmission();
  
  Wire.requestFrom(SLAVE_ADDR, 2);
  if (Wire.available() == 2) {
    int highByte = Wire.read();
    int lowByte = Wire.read();
    ldrValue = (highByte << 8) | lowByte;
    Serial.println("받은 LDR 값: " + String(ldrValue));
  }

  server.handleClient();
  delay(1000);
}
