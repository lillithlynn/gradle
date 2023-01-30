/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.caching.internal.controller;

import com.google.common.collect.Streams;
import com.google.common.io.Closer;
import org.apache.commons.io.IOUtils;
import org.gradle.caching.BuildCacheEntryWriter;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class DefaultNextGenBuildCacheAccess implements NextGenBuildCacheAccess {

    // TODO Move all thread-local buffers to a shared service
    // TODO Make buffer size configurable
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final ThreadLocal<byte[]> COPY_BUFFERS = ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);

    private final BuildCacheService local;
    private final BuildCacheService remote;

    public DefaultNextGenBuildCacheAccess(BuildCacheService local, BuildCacheService remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override
    public void load(Iterable<BuildCacheKey> keys, BiConsumer<BuildCacheKey, InputStream> processor) {
        // TODO Fan out to multiple threads
        Streams.stream(keys).forEach(key -> {
            boolean foundLocally = local.load(key, input -> processor.accept(key, input));
            if (foundLocally) {
                return;
            }
            remote.load(key, input -> {
                // TODO Make this work for large pieces of content, too
                ByteArrayOutputStream temp = new ByteArrayOutputStream();
                IOUtils.copyLarge(input, temp, COPY_BUFFERS.get());
                byte[] data = temp.toByteArray();
                // Mirror data in local cache
                local.store(key, new BuildCacheEntryWriter() {
                    @Override
                    public void writeTo(OutputStream output) throws IOException {
                        output.write(data);
                    }

                    @Override
                    public long getSize() {
                        return data.length;
                    }
                });
                processor.accept(key, new ByteArrayInputStream(data));
            });
        });
    }

    @Override
    public void store(Iterable<BuildCacheKey> keys, Function<BuildCacheKey, BuildCacheEntryWriter> processor) {
        // TODO Fan out to multiple threads
        Streams.stream(keys).forEach(key -> {
            BuildCacheEntryWriter writer = processor.apply(key);
            local.store(key, writer);
            remote.store(key, writer);
        });
    }

    @Override
    public void close() throws IOException {
        Closer closer = Closer.create();
        closer.register(local);
        closer.register(remote);
        closer.close();
    }
}
