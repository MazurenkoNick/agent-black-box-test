/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.msa.utils;

import org.slf4j.Logger;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;

public class CleanLogConsumer extends Slf4jLogConsumer {

    private final Logger logger;
    private final String prefix;

    public CleanLogConsumer(Logger logger, String prefix) {
        super(logger);
        this.logger = logger;
        this.prefix = prefix;
    }

    @Override
    public void accept(OutputFrame outputFrame) {
        String utf8String = outputFrame.getUtf8StringWithoutLineEnding();
        if (utf8String != null && !utf8String.isBlank()) {
            String type = outputFrame.getType() == OutputFrame.OutputType.STDERR ? "STDERR" : "STDOUT";
            logger.info("[{}] {}: {}", prefix, type, utf8String);
        }
    }
}
