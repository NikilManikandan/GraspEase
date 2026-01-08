/*
  ESP-NOW + BLE Receiver with Joystick, IMU, Switch & Pedal Control (ESP32)

  Receives ESP-NOW packets from:
   1) Gateway ESP32 -> { mode, control, angle }
   2) Switch ESP32  -> { angle }
   3) Pedal ESP32   -> { angle }

  BLE (Dextra-Orthosis):
   Commands: GRASP, HOLD, RELEASE, STOP(=REHAB)

  All sources ultimately drive 'pos' (0..180) and call moveAll(pos).
*/

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <Wire.h>
#include <Adafruit_PWMServoDriver.h>

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>

// =====================================================
//  SERVO DRIVER & MAPPING
// =====================================================
Adafruit_PWMServoDriver pwm = Adafruit_PWMServoDriver();

#define SERVO_MIN  500
#define SERVO_MAX  2400

int servoList[5] = {0, 3, 4, 7, 8};
const int N_SERVOS = 5;

int pos = 0;

int angleToPWM(int angle) {
  angle = constrain(angle, 0, 180);
  float pulse_us = SERVO_MIN + ((float)angle / 180.0f) * (SERVO_MAX - SERVO_MIN);
  return (int)((pulse_us / 20000.0f) * 4095.0f);
}

void moveAll(int ang) {
  ang = constrain(ang, 0, 180);
  for (int i = 0; i < N_SERVOS; i++) {
    int ch = servoList[i];
    int realAngle = (ch == 4 || ch == 7) ? (180 - ang) : ang;
    pwm.setPWM(ch, 0, angleToPWM(realAngle));
  }
}

// =====================================================
//  ESP-NOW PACKETS
// =====================================================
typedef struct {
  char mode[20];
  char control[20];
  int  angle;
} GatewayPacket;

typedef struct {
  int angle;
} SimpleAnglePacket;

GatewayPacket gatewayPkt;
SimpleAnglePacket simplePkt;

// =====================================================
//  BLE DEFINITIONS
// =====================================================
#define SERVICE_UUID        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHAR_WRITE_UUID     "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHAR_NOTIFY_UUID    "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

#define CMD_GRASP    0x01
#define CMD_HOLD     0x02
#define CMD_RELEASE  0x03
#define CMD_STOP     0x04

#define RESP_ACK     0x05
#define RESP_STATE   0x07
#define RESP_DONE    0x08

enum DevState : uint8_t {
  IDLE, HOLDING, RELEASING, REHAB, GRASPING
};

volatile DevState state = IDLE;
BLECharacteristic *pNotifyChar = nullptr;

unsigned long lastStep = 0;
#define STEP_DELAY_MS 20

// =====================================================
//  BLE HELPERS
// =====================================================
void notify(uint8_t *d, size_t l) {
  if (!pNotifyChar) return;
  pNotifyChar->setValue(d, l);
  pNotifyChar->notify();
}

void sendState(uint8_t s) {
  uint8_t d[2] = { RESP_STATE, s };
  notify(d, 2);
}

void sendDone() {
  uint8_t d = RESP_DONE;
  notify(&d, 1);
}

// =====================================================
//  BLE STATE MACHINE
// =====================================================
void tickRelease() {
  if (millis() - lastStep < STEP_DELAY_MS) return;
  lastStep = millis();

  if (pos > 0) {
    pos--;
    moveAll(pos);
  } else {
    state = IDLE;
    sendState(state);
    sendDone();
  }
}

void tickGrasp() {
  if (millis() - lastStep < STEP_DELAY_MS) return;
  lastStep = millis();

  if (pos < 180) {
    pos++;
    moveAll(pos);
  } else {
    state = HOLDING;
    sendState(state);
    sendDone();
  }
}

// =====================================================
//  BLE CALLBACKS
// =====================================================
class WriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    uint8_t cmd = pChar->getValue()[0];
    uint8_t ack = RESP_ACK;
    notify(&ack, 1);

    switch (cmd) {
      case CMD_GRASP:   state = GRASPING; lastStep = millis(); break;
      case CMD_HOLD:    state = HOLDING; break;
      case CMD_RELEASE: state = RELEASING; lastStep = millis(); break;
      case CMD_STOP:    state = IDLE; break;
    }
    sendState(state);
  }
};

class ServerCallback : public BLEServerCallbacks {
  void onDisconnect(BLEServer*) override {
    BLEDevice::startAdvertising();
  }
};

// =====================================================
//  ESP-NOW RECEIVE
// =====================================================
void onReceive(const uint8_t*, const uint8_t *data, int len) {

  if (len == sizeof(GatewayPacket)) {
    memcpy(&gatewayPkt, data, sizeof(gatewayPkt));

    if (!strcmp(gatewayPkt.control, "Reset")) {
      pos = 0;
      moveAll(0);
      return;
    }

    pos = constrain(gatewayPkt.angle, 0, 180);
    moveAll(pos);
  }

  else if (len == sizeof(SimpleAnglePacket)) {
    memcpy(&simplePkt, data, sizeof(simplePkt));
    pos = constrain(simplePkt.angle, 0, 180);
    moveAll(pos);
  }
}

// =====================================================
//  SETUP
// =====================================================
void setup() {
  Serial.begin(115200);

  Wire.begin();
  pwm.begin();
  pwm.setPWMFreq(50);
  moveAll(0);

  WiFi.mode(WIFI_STA);
  esp_now_init();
  esp_now_register_recv_cb(onReceive);

  BLEDevice::init("Dextra-Orthosis");
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallback());

  BLEService *service = server->createService(SERVICE_UUID);
  BLECharacteristic *pWrite =
    service->createCharacteristic(CHAR_WRITE_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);

  pWrite->setCallbacks(new WriteCallbacks());

  pNotifyChar =
    service->createCharacteristic(CHAR_NOTIFY_UUID,
      BLECharacteristic::PROPERTY_NOTIFY);

  pNotifyChar->addDescriptor(new BLE2902());

  service->start();
  BLEDevice::getAdvertising()->start();

  Serial.println("Servo Receiver Ready");
}

// =====================================================
//  LOOP
// =====================================================
void loop() {
  switch (state) {
    case GRASPING:  tickGrasp(); break;
    case RELEASING: tickRelease(); break;
    case HOLDING:
    case IDLE:
    default:
      break;
  }
}
