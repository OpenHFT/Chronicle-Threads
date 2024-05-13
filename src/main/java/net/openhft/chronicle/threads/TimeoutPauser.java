/*
 * Copyright 2016-2020 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.threads;

@Deprecated(/* to be removed in x.27, use YieldingPauser */)
public class TimeoutPauser extends YieldingPauser implements TimingPauser {

    /**
     * first it will busy wait, then it will yield.
     *
     * @param minBusy the min number of times it will go around doing nothing, after this is reached it will then start to yield
     */
    public TimeoutPauser(int minBusy) {
        super(minBusy);
    }
}
