/* =====================================================================
   1951 Rat Rod — relay controller
   Board:   Arduino Uno / Nano (ATmega328P)
   Role:    drive an 8-channel relay board, nothing else.

   Commands arrive over the USB cable from the Android app, on the
   hardware serial port at 115200 baud, as one-line ASCII frames.

   Commands (newline terminated)
     S            -> report state
     K            -> heartbeat  (must arrive at least every 3 s)
     R<ch>:<0|1>  -> ch 1..8, 0 = off 1 = on
     X            -> all off

   Replies
     S:01010000   -> 8 bit state, ch1 first
     A:<ch>:<0|1> -> acknowledged a channel change
     E:...        -> error

   SAFETY, all enforced here in firmware and not in the app:
     * every relay is driven OFF before its pin becomes an output
     * channel 1 (starter) self-limits to MAX_STARTER_MS
     * if no heartbeat arrives for LINK_TIMEOUT_MS, everything is cut
       except the channels in LINK_HOLD_MASK (ignition, fuel pump) so
       losing WiFi cannot kill a running engine
   ===================================================================== */

// ---------- configuration ----------
// Relay inputs. Ch1..Ch8 -> D2..D9.  D0/D1 are the serial port, do not use.
const uint8_t RELAY_PIN[8] = { 2, 3, 4, 5, 6, 7, 8, 9 };

// Most cheap 8-way boards are active LOW (IN pulled low = relay on).
// If yours switches backwards, set this to 0.
#define RELAY_ACTIVE_LOW 1

const uint32_t LINK_TIMEOUT_MS = 3000;  // no heartbeat -> fail safe (app beats every 700 ms)
const uint32_t MAX_STARTER_MS  = 5000;  // hard cap on channel 1
const uint8_t  STAGGER_MS      = 45;    // gap between relay closures

// Bit 0 = ch1 ... bit 7 = ch8.
// If the phone drops off the cable, hold whatever keeps the car driving and
// safe at night: ignition (ch2), fuel pump (ch3), fan (ch5), lights (ch6).
const uint8_t LINK_HOLD_MASK = 0b00110110;

// Per-channel maximum on-time; 0 = unlimited.
const uint32_t MAX_ON_MS[8] = { MAX_STARTER_MS, 0, 0, 0, 0, 0, 0, 0 };

// ---------- state ----------
uint8_t  state = 0;                 // relay states, bit0 = ch1
uint32_t onSince[8];
uint32_t lastRx = 0;
bool     degraded = false;
char     buf[16];
uint8_t  blen = 0;

// ---------- relay driving ----------
inline void writeRelay(uint8_t i, bool on) {
#if RELAY_ACTIVE_LOW
  digitalWrite(RELAY_PIN[i], on ? LOW : HIGH);
#else
  digitalWrite(RELAY_PIN[i], on ? HIGH : LOW);
#endif
}

void report() {
  Serial.print(F("S:"));
  for (uint8_t i = 0; i < 8; i++) Serial.write(bitRead(state, i) ? '1' : '0');
  Serial.println();
}

void applyChannel(uint8_t i, bool on) {
  bool cur = bitRead(state, i);
  if (on == cur) return;

  if (on) {
    bitSet(state, i);
    writeRelay(i, true);
    onSince[i] = millis();
    if (STAGGER_MS) delay(STAGGER_MS);   // don't slam the whole board at once
  } else {
    bitClear(state, i);
    writeRelay(i, false);
  }
}

void allOff(uint8_t keepMask) {
  for (int8_t i = 7; i >= 0; i--) {
    if (keepMask & (1 << i)) continue;
    if (bitRead(state, i)) {
      bitClear(state, i);
      writeRelay(i, false);
    }
  }
}

// ---------- command parsing ----------
void handle(char *b) {
  if (!b[0]) return;

  switch (b[0]) {
    case 'S':
      report();
      break;

    case 'K':                       // heartbeat only
      break;

    case 'X':
      allOff(0);
      report();
      break;

    case 'R': {
      char *colon = strchr(b, ':');
      if (!colon) { Serial.println(F("E:SYNTAX")); break; }
      *colon = '\0';
      int ch = atoi(b + 1);
      int on = atoi(colon + 1);
      if (ch < 1 || ch > 8) { Serial.println(F("E:CH")); break; }
      applyChannel(ch - 1, on != 0);
      Serial.print(F("A:"));
      Serial.print(ch);
      Serial.print(':');
      Serial.println(bitRead(state, ch - 1) ? 1 : 0);
      break;
    }

    default:
      Serial.println(F("E:CMD"));
  }
}

// ---------- setup ----------
void setup() {
  // Pull the relay drivers to the "off" level BEFORE they become outputs,
  // so there is no pulse on power-up.
  for (uint8_t i = 0; i < 8; i++) {
    digitalWrite(RELAY_PIN[i],
#if RELAY_ACTIVE_LOW
                 HIGH
#else
                 LOW
#endif
    );
    pinMode(RELAY_PIN[i], OUTPUT);
    writeRelay(i, false);
  }

  state = 0;
  Serial.begin(115200);
  delay(50);
  Serial.println(F("BOOT:rat-rod-relay"));
  report();
  lastRx = millis();
}

// ---------- loop ----------
void loop() {
  uint32_t now = millis();

  // serial in
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      buf[blen] = '\0';
      handle(buf);
      blen = 0;
      lastRx = now;
      degraded = false;
    } else if (c != '\r' && blen < sizeof(buf) - 1) {
      buf[blen++] = c;
    }
  }

  // link watchdog
  if (!degraded && (now - lastRx) > LINK_TIMEOUT_MS) {
    degraded = true;
    allOff(LINK_HOLD_MASK);
    Serial.println(F("W:link-lost"));
    report();
  }

  // per-channel on-time limits (starter)
  for (uint8_t i = 0; i < 8; i++) {
    if (!MAX_ON_MS[i]) continue;
    if (bitRead(state, i) && (now - onSince[i]) > MAX_ON_MS[i]) {
      applyChannel(i, false);
      Serial.print(F("W:timeout:"));
      Serial.println(i + 1);
      report();
    }
  }
}
