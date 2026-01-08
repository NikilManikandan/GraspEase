#include <esp_now.h>
#include <WiFi.h>

// Switch pins
const int graspPin   = 32;     // switch for + direction
const int releasePin = 33;     // switch for - direction

int pos = 0;                   // angle to send (0–180)

// Receiver MAC address (servo ESP)
// ⚠️ Make sure this matches your receiver's MAC

//uint8_t receiverMAC[] = {0x80, 0xF3, 0xDA, 0x41, 0x5D, 0x18};
//80:F3:DA:41:5D:18
uint8_t receiverMAC[] = {0x80, 0xF3, 0xDA, 0x40, 0xE4, 0xC0};
// Packet structure MUST match receiver and paddle
typedef struct {
  int angle;
} dataPacket;

dataPacket packet;

int lastPos = -1;

// Optional: send status callback
void onDataSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  Serial.print("Switch send status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Success" : "Fail");
}

void setup() {
  Serial.begin(115200);

  pinMode(graspPin,   INPUT_PULLUP);
  pinMode(releasePin, INPUT_PULLUP);

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

  Serial.print("Switch controller ready. My MAC: ");
  Serial.println(WiFi.macAddress());
}

void loop() {
  bool grasp   = (digitalRead(graspPin)   == LOW);  // button pressed = LOW
  bool release = (digitalRead(releasePin) == LOW);

  int newPos = pos;

  if (grasp) {
    if (newPos < 180) newPos++;
  } else if (release) {
    if (newPos > 0) newPos--;
  }

  // Only send when angle actually changes
  if (newPos != lastPos) {
    pos = newPos;
    lastPos = newPos;

    packet.angle = pos;

    esp_err_t result = esp_now_send(receiverMAC, (uint8_t*)&packet, sizeof(packet));

    Serial.print("Switch angle: ");
    Serial.print(pos);
    Serial.print(" | send: ");
    Serial.println(result == ESP_OK ? "OK" : "ERR");
  }

  delay(15);   // same feel as your original code
}
