/*
 * Copyright 2016 Denis Andreev.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package free.lucifer.chiplib;

import free.lucifer.chiplib.boards.AXP209;
import free.lucifer.chiplib.boards.GR8;
import free.lucifer.chiplib.boards.IOBoard;
import free.lucifer.chiplib.boards.PCF8574A;
import free.lucifer.chiplib.boards.R8;
import free.lucifer.chiplib.modules.Task;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class ChipPro implements IOBoard, Runnable {

    private Map<Class<? extends IOBoard>, IOBoard> registry = new HashMap<>();
    private boolean open = false;

    public static final ChipPro I = new ChipPro();

    private final NullBoard nullBoard = new NullBoard();
    private final List<Enum> poolPin = new CopyOnWriteArrayList<>();
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final Map<Pin, List<PinListener>> listeners = new ConcurrentHashMap<Pin, List<PinListener>>();

    private static final long POOL_INTERVAL = TimeUnit.MILLISECONDS.toNanos(20);
    private Thread poolThread;

    private ChipPro() {
        registry.put(GR8.class, new GR8());
        registry.put(AXP209.class, new AXP209(0, 0x34));
        registry.put(PCF8574A.class, new PCF8574A(2, 0x38));

        embedIO();
        parsePins();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                close();
            }
        });
    }

    private void embedIO() {
        for (Pin pin : Pin.values()) {
            pin.managerInstance = registry.get(pin.manager);
        }
    }

    private void parsePins() {
        for (GR8.ChipPin pin : GR8.ChipPin.values()) {
            Pin p = Pin.valueOf(pin.toString());
            p.customPin = pin;
        }
    }

    @Override
    public void run() {
        long shift = 0;
        while (open) {
            if (POOL_INTERVAL - shift > 0) {
                LockSupport.parkNanos(POOL_INTERVAL - shift);
            }
            long nano = System.nanoTime();
            for (Enum p : poolPin) {
                Pin pin = ((ChipPro.Pin) p);
                if (pin.mode == Pin.PinMode.INPUT) {
                    pin.lastReport = analogRead(pin);
                } else {
                    pin.lastReport = digiatalRead(pin);
                }

                if (pin.report != pin.lastReport) {
                    emit(pin);
                    pin.report = pin.lastReport;
                }
            }
            for (Task task : tasks) {
                task.delta += POOL_INTERVAL;
                if (task.delta > task.period) {
                    task.delta -= task.period;
                    task.doTask();
                }
            }
            shift = System.nanoTime() - nano;
        }
    }

    public void subscribePinChange(Pin pin, PinListener listener) {
        List<PinListener> list = listeners.get(pin);
        if (list == null) {
            list = new CopyOnWriteArrayList<>();
            listeners.put(pin, list);
        }

        list.add(listener);
    }

    public void unSubscribePinChange(Pin pin, PinListener listener) {
        List<PinListener> list = listeners.get(pin);
        if (list == null) {
            list = new CopyOnWriteArrayList<>();
            listeners.put(pin, list);
        }

        list.remove(listener);
    }

    public void addTask(Task task) {
        tasks.add(task);
    }

    public void removeTask(Task task) {
        tasks.remove(task);
    }

    @Override
    public void open() {
        for (IOBoard io : registry.values()) {
            io.open();
        }
        open = true;
        poolThread = new Thread(this);
        poolThread.start();
    }

    @Override
    public void close() {
        for (IOBoard io : registry.values()) {
            io.close();
        }
        open = false;
    }

    @Override
    public void pinMode(Enum pin, Enum mode) {
        ((ChipPro.Pin) pin).mode = (ChipPro.Pin.PinMode) mode;

        if (mode == Pin.PinMode.INPUT || mode == Pin.PinMode.PWM || mode == Pin.PinMode.ANALOG) {
            if (!poolPin.contains(pin)) {
                poolPin.add(pin);
            }
        } else if (poolPin.contains(pin)) {
            poolPin.remove(pin);
        }

        ((ChipPro.Pin) pin).managerInstance.pinMode(pin, mode);
    }

    @Override
    public void pwmWrite(Enum pin, int value) {
        ((ChipPro.Pin) pin).managerInstance.pwmWrite(pin, value);
    }

    @Override
    public int analogRead(Enum pin) {
        return ((ChipPro.Pin) pin).managerInstance.analogRead(pin);
    }

    @Override
    public void analogWrite(Enum pin, int value) {
        pwmWrite(pin, value);
    }

    @Override
    public int digiatalRead(Enum pin) {
        return ((ChipPro.Pin) pin).managerInstance.digiatalRead(pin);
    }

    @Override
    public void digitalWrite(Enum pin, int value) {
        ((ChipPro.Pin) pin).managerInstance.digitalWrite(pin, value);
    }

    private void emit(Pin pin) {
        List<PinListener> list = listeners.get(pin);
        if (list == null) {
            return;
        }
        for (PinListener listener : list) {
            listener.onPinChange(pin, pin.report, pin.lastReport);
        }
    }

    public void shiftOut(Pin dataPin, Pin clockPin, byte value) {
        shiftOut(dataPin, clockPin, value, true);
    }

    public void shiftOut(Pin dataPin, Pin clockPin, byte value, boolean isBigEndian) {
        for (int i = 0; i < 8; i++) {
            digitalWrite(clockPin, 0);
            if (isBigEndian) {
                digitalWrite(dataPin, (value & (1 << (7 - i))) == 0 ? 0 : 1);
            } else {
                digitalWrite(dataPin, (value & (1 << i)) == 0 ? 0 : 1);
            }
            digitalWrite(clockPin, 1);
        }
    }

    public void delay(int ms) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    public void delayMicro(int ms) {
        LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(ms));
    }

    public static enum EventType {
        DATA_CHANGE,
    }

    public static interface PinListener {

        void onPinChange(Pin pin, int old, int val);
    }

    private static class NullBoard implements IOBoard {

        @Override
        public void open() {

        }

        @Override
        public void close() {

        }

        @Override
        public void pinMode(Enum pin, Enum mode) {

        }

        @Override
        public void pwmWrite(Enum pin, int value) {

        }

        @Override
        public int analogRead(Enum pin) {
            return -1;
        }

        @Override
        public void analogWrite(Enum pin, int value) {

        }

        @Override
        public int digiatalRead(Enum pin) {
            return -1;
        }

        @Override
        public void digitalWrite(Enum pin, int value) {

        }

    }

    public static enum Pin {
        PWM0(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT, PinMode.PWM}, GR8.class),
        PWM1(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT, PinMode.PWM}, GR8.class),
        LRADC(new PinMode[]{PinMode.ANALOG}, GR8.class),
        CSIPCK(new PinMode[]{PinMode.INPUT}, GR8.class),
        CSIMCLK(new PinMode[]{PinMode.INPUT}, GR8.class),
        CSIHSYNC(new PinMode[]{PinMode.INPUT}, GR8.class),
        CSIVSYNC(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID0(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID1(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID2(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID3(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID4(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID5(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID6(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        CSID7(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        TWI1_SCK(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        TWI1_SDA(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        UART2_TX(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        UART2_RX(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        UART2_CTS(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        UART2_RTS(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        UART1_TX(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        UART1_RX(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        I2S_MCLK(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        I2S_BLCK(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        I2S_LCLK(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        I2S_DO(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class),
        I2S_DI(new PinMode[]{PinMode.INPUT, PinMode.OUTPUT}, GR8.class);

        public final PinMode[] modes;
        private PinMode mode;
        private int report;
        private int lastReport;
        private int analogChannel;
        public final Class<? extends IOBoard> manager;
        public IOBoard managerInstance;
        public GR8.ChipPin customPin;

        private Pin() {
            this.modes = new PinMode[]{};
            this.manager = null;
            this.report = -1;
            this.lastReport = -1;
            this.mode = PinMode.NONE;
            this.analogChannel = 127;
        }

        private Pin(PinMode[] modes, Class<? extends IOBoard> manager) {
            this.modes = modes;
            this.mode = PinMode.NONE;
            this.report = -1;
            this.lastReport = -1;
            this.analogChannel = 127;
            this.manager = manager;
        }

        private Pin(PinMode[] modes, PinMode mode, int report, int analogChannel, Class<? extends IOBoard> manager) {
            this.modes = modes;
            this.mode = mode;
            this.report = -1;
            this.lastReport = -1;
            this.analogChannel = analogChannel;
            this.manager = manager;
        }

        private Pin(PinMode[] modes, PinMode mode, int report, int analogChannel) {
            this.modes = modes;
            this.mode = mode;
            this.report = -1;
            this.lastReport = -1;
            this.analogChannel = analogChannel;
            this.manager = null;
        }

        public PinMode getMode() {
            return mode;
        }

        public void setMode(PinMode mode) {
            this.mode = mode;
        }

        public int getReport() {
            return report;
        }

        public void setReport(int report) {
            this.report = report;
        }

        public int getLastReport() {
            return lastReport;
        }

        public void setLastReport(int lastReport) {
            this.lastReport = lastReport;
        }

        public int getAnalogChannel() {
            return analogChannel;
        }

        public void setAnalogChannel(int analogChannel) {
            this.analogChannel = analogChannel;
        }

        public static enum PinMode {
            NONE(-1),
            INPUT(0),
            OUTPUT(1),
            ANALOG(2),
            PWM(3),
            SERVO(4),
            INPUT_PULLUP(5);

            public final int id;

            private PinMode(int id) {
                this.id = id;
            }
        }
    }
}
