/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.palantir.conjure.java.api.errors.RemoteException;
import com.palantir.conjure.java.serialization.ObjectMappers;
import com.palantir.dialogue.com.palantir.conjure.verification.server.EndpointName;
import com.palantir.dialogue.com.palantir.conjure.verification.server.SingleHeaderServiceBlocking;
import com.palantir.dialogue.com.palantir.conjure.verification.server.SinglePathParamServiceBlocking;
import com.palantir.dialogue.com.palantir.conjure.verification.server.SingleQueryParamServiceBlocking;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.commons.lang3.ClassUtils;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class SingleParamServicesTest {

    @ClassRule
    public static final VerificationServerRule server = new VerificationServerRule();

    private static final SafeLogger log = SafeLoggerFactory.get(SingleParamServicesTest.class);
    private static final ObjectMapper objectMapper = ObjectMappers.newClientObjectMapper();
    private static ImmutableMap<String, Object> servicesMaps = ImmutableMap.of(
            "singlePathParamService",
            server.client(SinglePathParamServiceBlocking.class),
            "singleHeaderService",
            server.client(SingleHeaderServiceBlocking.class),
            "singleQueryParamService",
            server.client(SingleQueryParamServiceBlocking.class));

    @Parameterized.Parameter(0)
    public String serviceName;

    @Parameterized.Parameter(1)
    public EndpointName endpointName;

    @Parameterized.Parameter(2)
    public int index;

    @Parameterized.Parameter(3)
    public String jsonString;

    @Parameterized.Parameters(name = "{0}/{1}({3})")
    public static Collection<Object[]> data() {
        List<Object[]> objects = new ArrayList<>();
        Cases.TEST_CASES.getSingleHeaderService().forEach((endpointName, singleHeaderTestCases) -> {
            int size = singleHeaderTestCases.size();
            IntStream.range(0, size)
                    .forEach(i -> objects.add(
                            new Object[] {"singleHeaderService", endpointName, i, singleHeaderTestCases.get(i)}));
        });

        Cases.TEST_CASES.getSinglePathParamService().forEach((endpointName, singleHeaderTestCases) -> {
            int size = singleHeaderTestCases.size();
            IntStream.range(0, size)
                    .forEach(i -> objects.add(
                            new Object[] {"singlePathParamService", endpointName, i, singleHeaderTestCases.get(i)}));
        });

        Cases.TEST_CASES.getSingleQueryParamService().forEach((endpointName, singleQueryTestCases) -> {
            int size = singleQueryTestCases.size();
            IntStream.range(0, size)
                    .forEach(i -> objects.add(
                            new Object[] {"singleQueryParamService", endpointName, i, singleQueryTestCases.get(i)}));
        });

        return objects;
    }

    @Test
    public void runTestCase() throws Exception {
        Assume.assumeFalse(Cases.shouldIgnore(endpointName, jsonString));

        log.info(
                "Invoking {} {}({})",
                SafeArg.of("serviceName", serviceName),
                SafeArg.of("endpointName", endpointName),
                SafeArg.of("jsonString", jsonString));

        Object service = servicesMaps.get(serviceName);
        for (Method method : servicesMaps.get(serviceName).getClass().getMethods()) {
            String name = method.getName();
            // Need to set accessible true work around dialogues anonymous class impl
            method.setAccessible(true);

            if (method.getParameterCount() == 1) {
                // conjure-java generates `default` methods for optional query params, we don't want to call these
                continue;
            }

            if (endpointName.get().equals(name)) {
                try {
                    // HACKHACK, index parameter order is different for different services.
                    if ("singleHeaderService".equals(serviceName)) {
                        Type type = getRawType(method.getGenericParameterTypes()[0]);
                        Class<?> cls = ClassUtils.getClass(type.getTypeName());
                        method.invoke(service, objectMapper.readValue(jsonString, cls), index);
                    } else {
                        Type type = getRawType(method.getGenericParameterTypes()[1]);
                        Class<?> cls = ClassUtils.getClass(type.getTypeName());
                        method.invoke(service, index, objectMapper.readValue(jsonString, cls));
                    }

                    log.info(
                            "Successfully post param to endpoint {} and index {}",
                            SafeArg.of("endpointName", endpointName),
                            SafeArg.of("index", index));
                } catch (RemoteException e) {
                    log.error(
                            "Caught exception with params: {}",
                            SafeArg.of("errorParameters", e.getError().parameters()),
                            e);
                    throw e;
                }
            }
        }
    }

    private static Type getRawType(Type type) {
        if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getRawType();
        }
        return type;
    }
}
