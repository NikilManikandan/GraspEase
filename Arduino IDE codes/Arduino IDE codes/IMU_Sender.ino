/* CLEANED SENDER (MPU6050 -> ESP-NOW)
   Serial commands: CAL, STOP, RUN, SETDB <val>, ?
   Sends: { pitch, calib_mean, deadband } to peerMAC
*/

#include <Wire.h>
#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>

const uint8_t MPU_ADDR = 0x68;

// ---------- UPDATED MAC ADDRESS ----------
const uint8_t peerMAC[6] = {0x80, 0xF3, 0xDA, 0x42, 0x80, 0xAC };
// -----------------------------------------

// timing
unsigned long lastTime = 0;
const unsigned long SEND_INTERVAL_MS = 50; // 20 Hz

// sensor/filter
float pitch = 0.0f;
float gyroYoffset = 0.0f;
const float alpha = 0.98f;

// calibration
float calib_mean = 0.0f;
float deadband = 2.5f;
const unsigned long CALIB_MS = 7000UL;

// control
bool sendingEnabled = true;

typedef struct {
  float pitch;
  float calib_mean;
  float deadband;
} payload_t;
payload_t payload;

// ---------------- MPU ----------------
void setupMPU() {
  Wire.begin(21, 22);
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x6B);
  Wire.write(0x00);
  Wire.endTransmission(true);
  delay(100);
}

void readMPU_raw(float &accX, float &accY, float &accZ,
                 float &gyroX, float &gyroY, float &gyroZ) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x3B);
  Wire.endTransmission(false);
  Wire.requestFrom((uint8_t)MPU_ADDR, (size_t)14, true);

  int16_t aX = Wire.read() << 8 | Wire.read();
  int16_t aY = Wire.read() << 8 | Wire.read();
  int16_t aZ = Wire.read() << 8 | Wire.read();
  Wire.read(); Wire.read();
  int16_t gX = Wire.read() << 8 | Wire.read();
  int16_t gY = Wire.read() << 8 | Wire.read();
  int16_t gZ = Wire.read() << 8 | Wire.read();

  accX = aX / 16384.0f;
  accY = aY / 16384.0f;
  accZ = aZ / 16384.0f;

  gyroX = gX / 131.0f;
  gyroY = gY / 131.0f;
  gyroZ = gZ / 131.0f;
}

void calibrateGyroOffsets() {
  float gy = 0.0f;
  const int samples = 200;

  for (int i = 0; i < samples; ++i) {
    float ax, ay, az, gX, gY, gZ;
    readMPU_raw(ax, ay, az, gX, gY, gZ);
    gy += gY;
    delay(5);
  }

  gyroYoffset = gy / samples;
}

void doCalibrationWindow() {
  Serial.println("Calibration: keep MPU steady at desired reference position...");

  unsigned long start = millis();
  float sum = 0.0f;
  int count = 0;

  while (millis() - start < CALIB_MS) {
    float ax, ay, az, gX, gY, gZ;
    readMPU_raw(ax, ay, az, gX, gY, gZ);
    float pitch_acc = atan2(ax, sqrt(ay * ay + az * az)) * 180.0f / PI;

    sum += pitch_acc;
    ++count;
    delay(10);
  }

  if (count > 0) {
    calib_mean = sum / count;
    Serial.print("Calibration complete. calib_mean = ");
    Serial.println(calib_mean, 3);
  } else {
    Serial.println("Calibration failed (no samples)");
  }
}

// ---------------- ESP-NOW helpers ----------------
void printEspErr(int e) {
  Serial.print(e);
  Serial.print(" -> ");
  switch (e) {
    case ESP_OK: Serial.println("ESP_OK"); break;
    case ESP_ERR_ESPNOW_NOT_INIT: Serial.println("NOT_INIT"); break;
    case ESP_ERR_ESPNOW_ARG: Serial.println("BAD_ARG"); break;
    case ESP_ERR_ESPNOW_INTERNAL: Serial.println("INTERNAL_ERR"); break;
    case ESP_ERR_ESPNOW_NO_MEM: Serial.println("NO_MEM"); break;
    case ESP_ERR_ESPNOW_EXIST: Serial.println("PEER_EXISTS"); break;
    case ESP_ERR_ESPNOW_NOT_FOUND: Serial.println("PEER_NOT_FOUND"); break;
    case ESP_ERR_ESPNOW_IF: Serial.println("IF_ERROR"); break;
    default: Serial.println("UNKNOWN"); break;
  }
}

void initWifiAndEspNow() {
  WiFi.mode(WIFI_STA);
  delay(50);

  esp_err_t s = esp_wifi_start();
  Serial.print("esp_wifi_start -> "); printEspErr((int)s);

  int channel = 1;
  esp_err_t ch = esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE);
  Serial.print("esp_wifi_set_channel -> "); printEspErr((int)ch);

  uint8_t primary;
  wifi_second_chan_t secondary;
  esp_wifi_get_channel(&primary, &secondary);
  Serial.print("channel readback primary=");
  Serial.print(primary);
  Serial.print(" secondary=");
  Serial.println((int)secondary);

  esp_err_t initRes = esp_now_init();
  Serial.print("esp_now_init -> "); printEspErr((int)initRes);

  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, peerMAC, 6);
  peer.channel = channel;
  peer.encrypt = false;

  esp_err_t addRes = esp_now_add_peer(&peer);
  Serial.print("esp_now_add_peer -> "); printEspErr((int)addRes);

  esp_now_register_send_cb([](const uint8_t *mac, esp_now_send_status_t status) {
    Serial.print("SendCB status: ");
    Serial.println((int)status);
  });
}

// ---------------- Serial helpers ----------------
String readSerialLine() {
  static String line = "";
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n') {
      String out = line;
      line = "";
      out.trim();
      return out;
    }
    if (c != '\r') line += c;
  }
  return "";
}

void printStatus() {
  Serial.println("---- STATUS ----");
  Serial.print("Mode: ");
  Serial.println(sendingEnabled ? "RUN" : "STOP");
  Serial.print("Pitch: ");
  Serial.println(pitch, 3);
  Serial.print("Calib Mean: ");
  Serial.println(calib_mean, 3);
  Serial.print("Deadband: ");
  Serial.println(deadband, 3);
  Serial.println("----------------");
}

void processCommand(String cmdLine) {
  if (cmdLine.length() == 0) return;

  int sp = cmdLine.indexOf(' ');
  String cmd = (sp == -1) ? cmdLine : cmdLine.substring(0, sp);
  cmd.toUpperCase();

  if (cmd == "CAL") doCalibrationWindow();
  else if (cmd == "STOP") { sendingEnabled = false; Serial.println("STOP: sending disabled"); }
  else if (cmd == "RUN") { sendingEnabled = true; Serial.println("RUN: sending enabled"); }
  else if (cmd == "SETDB") {
    if (sp == -1) Serial.println("Usage: SETDB 3.5");
    else {
      deadband = cmdLine.substring(sp + 1).toFloat();
      Serial.print("deadband = ");
      Serial.println(deadband);
    }
  }
  else if (cmd == "?") printStatus();
  else {
    Serial.print("Unknown: ");
    Serial.println(cmdLine);
  }
}

// ---------------- Setup & Loop ----------------
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n=== SENDER (MPU) START ===");

  setupMPU();
  calibrateGyroOffsets();
  initWifiAndEspNow();

  Serial.println("Initial calibration...");
  doCalibrationWindow();

  lastTime = millis();
  Serial.println("Commands: CAL, STOP, RUN, SETDB <value>, ?");
}

void loop() {
  String line = readSerialLine();
  if (line.length()) processCommand(line);

  unsigned long now = millis();
  if (now - lastTime < SEND_INTERVAL_MS) return;

  float dt = (now - lastTime) / 1000.0f;
  lastTime = now;

  float ax, ay, az;
  float gX, gY, gZ;
  readMPU_raw(ax, ay, az, gX, gY, gZ);

  gY -= gyroYoffset;

  float pitch_acc =
    atan2(ax, sqrt(ay * ay + az * az)) * 180.0f / PI;

  pitch =
    alpha * (pitch + gY * dt) +
    (1.0f - alpha) * pitch_acc;

  payload.pitch = pitch;
  payload.calib_mean = calib_mean;
  payload.deadband = deadband;

  if (sendingEnabled) {
    esp_err_t res = esp_now_send(peerMAC, (uint8_t *)&payload, sizeof(payload));
    Serial.print("esp_now_send -> ");
    printEspErr((int)res);
  }
}
