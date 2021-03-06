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

package free.lucifer.chiplib.modules;

import free.lucifer.chiplib.Chip;
import java.util.concurrent.TimeUnit;

public class Led extends Task {

    private boolean active = false;
    private boolean taskAdded = false;
    private Chip.Pin pin;

    public Led(Chip.Pin pin) {
        Chip.I.pinMode(pin, Chip.Pin.PinMode.OUTPUT);
        this.pin = pin;
    }

    @Override
    public void doTask() {
        if (active) {
            off();
        } else {
            on();
        }
    }

    public void blink(long ms) {
        period = TimeUnit.MILLISECONDS.toNanos(ms);
        delta = 0;
        if (!taskAdded) {
            Chip.I.addTask(this);
            taskAdded = true;
        }
    }

    public void on() {
        active = true;
        Chip.I.digitalWrite(pin, 1);
    }

    public void off() {
        active = false;
        Chip.I.digitalWrite(pin, 0);
    }

    public void stopBlinking() {
        if (taskAdded) {
            Chip.I.removeTask(this);
            taskAdded = false;
        }
    }
}
