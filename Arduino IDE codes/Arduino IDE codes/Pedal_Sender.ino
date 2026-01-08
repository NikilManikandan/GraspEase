#include <esp_now.h>
#include <WiFi.h>

#define PEDAL_PIN 25   // one side to GPIO25, other side to GND

// Receiver ESP32 MAC address (servo board)

uint8_t receiverMAC[] = {0x80, 0xF3, 0xDA, 0x42, 0x80, 0xAC};

// Packet structure MUST MATCH receiver
typedef struct {
  int angle;
} dataPacket;

dataPacket packet;

int lastAngle = -1;

// Optional: callback to see send status
void onDataSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  Serial.print("Paddle send status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Success" : "Fail");
}

void setup() {
  Serial.begin(115200);

  pinMode(PEDAL_PIN, INPUT_PULLUP);  // internal pull-up

  WiFi.mode(WIFI_STA);

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed!");
    return;
  }

  esp_now_register_send_cb(onDataSent);

  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, receiverMAC, 6);
  peerInfo.channel = 0;
  peerInfo.encrypt = false;

  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("Failed to add peer");
    return;
  }

  Serial.print("Foot pedal controller ready. My MAC: ");
  Serial.println(WiFi.macAddress());
}

void loop() {
  // LOW when pressed (INPUT_PULLUP)
  bool pressed = (digitalRead(PEDAL_PIN) == LOW);

  int angle = pressed ? 180 : 0;

  // Only send if changed
  if (angle != lastAngle) {
    lastAngle = angle;

    packet.angle = angle;

    esp_err_t result = esp_now_send(receiverMAC, (uint8_t*)&packet, sizeof(packet));

    Serial.print("Pedal ");
    Serial.print(pressed ? "PRESSED" : "RELEASED");
    Serial.print(" -> angle: ");
    Serial.print(angle);
    Serial.print(" | send: ");
    Serial.println(result == ESP_OK ? "OK" : "ERR");
  }

  delay(20);
}
