#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7735.h>
#include <WiFi.h>
#include <esp_now.h>
#include <math.h>

// ================= TFT CONFIG =================
#define ST77XX_DARKGREY 0x7BEF
#define TFT_CS   5
#define TFT_DC   2
#define TFT_RST  4
#define TFT_SCK  18
#define TFT_MOSI 23

Adafruit_ST7735 tft(TFT_CS, TFT_DC, TFT_MOSI, TFT_SCK, TFT_RST);

// ---------- JOYSTICK ----------
#define JOY_X 32
#define JOY_Y 33
#define JOY_SW 25

// ---------- RECEIVER MAC ----------
uint8_t receiverMAC[] = {0x80, 0xF3, 0xDA, 0x40, 0xE4, 0xC0};

// ---------- PACKET FORMAT ----------
typedef struct {
  char mode[20];
  char control[20];
  int  angle;
} dataPacket;

dataPacket txData;

// ---------- PEDAL ----------
typedef struct {
  int angle;
} pedalPacket;

// ---------- IMU ----------
typedef struct {
  float pitch;
  float calib_mean;
  float deadband;
} imuPayload_t;

volatile bool imuHasData = false;
imuPayload_t lastImuPayload;

// ---------- MENU ----------
String menuItems[] = {
  "Joystick Control",
  "IMU Control",
  "Foot Pedal Control",
  "Settings",
  "Info"
};

const int menuCount = sizeof(menuItems) / sizeof(menuItems[0]);
int selected = 0;
int lastSelected = -1;
int activeModeIndex = -1;

bool inMode = false;
int clickCount = 0;
unsigned long lastClickTime = 0;
const int clickTimeout = 700;

unsigned long lastStepTime = 0;
unsigned long lastMoveTime = 0;
const int controlStepInterval = 15;
const int scrollDelay = 180;

int servoPos = 0;

// =====================================================
//  UI HELPERS
// =====================================================
void drawFooter(const char* text) {
  tft.fillRect(0, 140, 160, 20, ST77XX_BLACK);
  tft.setTextColor(ST77XX_DARKGREY);
  tft.setCursor(10, 146);
  tft.print(text);
}

void drawMenuStatic() {
  tft.fillScreen(ST77XX_BLACK);

  // Title
  tft.fillRect(0, 0, 160, 18, ST77XX_BLUE);
  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(8, 5);
  tft.print("CONTROL MODES");

  // Menu items
  for (int i = 0; i < menuCount; i++) {
    int y = 24 + i * 18;
    tft.setTextColor(ST77XX_CYAN);
    tft.setCursor(12, y);
    tft.print(menuItems[i]);
  }

  drawFooter("Move joystick | Press to select");

  lastSelected = selected;
  updateMenuSelection(-1, selected);
}

void updateMenuSelection(int oldIndex, int newIndex) {
  if (oldIndex >= 0) {
    int yOld = 22 + oldIndex * 18;
    tft.fillRect(0, yOld, 160, 18, ST77XX_BLACK);
    tft.setTextColor(ST77XX_CYAN);
    tft.setCursor(12, yOld + 2);
    tft.print(menuItems[oldIndex]);
  }

  int yNew = 22 + newIndex * 18;
  tft.fillRoundRect(4, yNew, 152, 18, 6, ST77XX_BLUE);
  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(12, yNew + 2);
  tft.print(menuItems[newIndex]);
}

void enterModeScreen(int idx) {
  tft.fillScreen(ST77XX_BLACK);

  tft.fillRect(0, 0, 160, 18, ST77XX_BLUE);
  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(8, 5);
  tft.print(menuItems[idx]);

  tft.drawFastHLine(0, 22, 160, ST77XX_DARKGREY);

  tft.setTextColor(ST77XX_YELLOW);
  tft.setCursor(20, 60);
  tft.print("Click 3 times");

  tft.setCursor(32, 80);
  tft.print("to return");

  clickCount = 0;
}

void drawExitDots(int count) {
  int startX = 60;
  int y = 110;

  tft.fillRect(40, y - 6, 80, 12, ST77XX_BLACK);

  for (int i = 0; i < count; i++) {
    tft.fillCircle(startX + i * 12, y, 4, ST77XX_YELLOW);
  }
}

// =====================================================
//  ESP-NOW
// =====================================================
void sendAngleToReceiver(const char* modeStr, const char* controlStr, int angle) {
  angle = constrain(angle, 0, 180);
  strncpy(txData.mode, modeStr, sizeof(txData.mode));
  strncpy(txData.control, controlStr, sizeof(txData.control));
  txData.mode[19] = 0;
  txData.control[19] = 0;
  txData.angle = angle;
  esp_now_send(receiverMAC, (uint8_t*)&txData, sizeof(txData));
}

void sendResetToReceiver() {
  servoPos = 0;
  sendAngleToReceiver("SYS", "Reset", 0);
}

// ---------- ESP-NOW RECEIVE ----------
void onDataRecv(const uint8_t*, const uint8_t *data, int len) {

  if (len == sizeof(imuPayload_t)) {
    memcpy(&lastImuPayload, data, sizeof(imuPayload_t));
    imuHasData = true;
    return;
  }

  if (len == sizeof(pedalPacket)) {
    pedalPacket p;
    memcpy(&p, data, sizeof(p));
    sendAngleToReceiver("Foot", "Foot Pedal Control", p.angle);

    tft.fillRect(0, 120, 160, 20, ST77XX_BLACK);
    tft.setTextColor(ST77XX_GREEN);
    tft.setCursor(10, 125);
    tft.print("Pedal: ");
    tft.print(p.angle);
  }
}

// =====================================================
//  MODES
// =====================================================
void handleJoystickMode() {
  if (millis() - lastStepTime < controlStepInterval) return;
  lastStepTime = millis();

  int x = analogRead(JOY_X);
  if (abs(x - 2048) < 120) return;

  if (x > 2500 && servoPos < 180) servoPos++;
  else if (x < 1500 && servoPos > 0) servoPos--;

  sendAngleToReceiver("Joystick", "Joystick Control", servoPos);
}

void handleIMUMode() {
  if (!imuHasData) return;
  if (millis() - lastStepTime < controlStepInterval) return;
  lastStepTime = millis();

  float err = lastImuPayload.pitch - lastImuPayload.calib_mean;
  if (fabs(err) < lastImuPayload.deadband) return;

  if (err > 0 && servoPos < 180) servoPos++;
  else if (err < 0 && servoPos > 0) servoPos--;

  sendAngleToReceiver("IMU", "IMU Control", servoPos);
}

// =====================================================
//  SETUP
// =====================================================
void setup() {
  Serial.begin(115200);
  pinMode(JOY_SW, INPUT_PULLUP);

  tft.initR(INITR_BLACKTAB);
  tft.setRotation(1);
  tft.fillScreen(ST77XX_BLACK);

  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(10, 10);
  tft.print("Booting...");

  WiFi.mode(WIFI_STA);
  esp_now_init();
  esp_now_register_recv_cb(onDataRecv);

  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, receiverMAC, 6);
  peerInfo.channel = 0;
  peerInfo.encrypt = false;
  esp_now_add_peer(&peerInfo);

  delay(300);
  drawMenuStatic();
}

// =====================================================
//  LOOP
// =====================================================
void loop() {
  int sw = digitalRead(JOY_SW);
  int y = analogRead(JOY_Y);

  if (inMode) {
    if (sw == LOW) {
      delay(200);
      unsigned long now = millis();
      clickCount = (now - lastClickTime < clickTimeout) ? clickCount + 1 : 1;
      lastClickTime = now;
      drawExitDots(clickCount);

      if (clickCount >= 3) {
        clickCount = 0;
        inMode = false;
        sendResetToReceiver();
        drawMenuStatic();
        return;
      }
    }

    if (activeModeIndex == 0) handleJoystickMode();
    else if (activeModeIndex == 1) handleIMUMode();
    return;
  }

  if (y > 3500 && millis() - lastMoveTime > scrollDelay) {
    if (selected < menuCount - 1) selected++;
  }

  if (y < 1000 && millis() - lastMoveTime > scrollDelay) {
    if (selected > 0) selected--;
  }

  if (selected != lastSelected) {
    updateMenuSelection(lastSelected, selected);
    lastSelected = selected;
    lastMoveTime = millis();
  }

  if (sw == LOW) {
    delay(200);
    inMode = true;
    activeModeIndex = selected;
    imuHasData = false;
    enterModeScreen(selected);
  }
}
