/*
 * Copyright (C) 2012 Facebook, Inc.
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
package io.airlift.drift.codec.idlannotations;

import io.airlift.drift.annotations.ThriftField;
import io.airlift.drift.annotations.ThriftIdlAnnotation;
import io.airlift.drift.annotations.ThriftStruct;

@ThriftStruct
public class BeanWithMatchingIdlAnnotationsMapsForField
{
    private int type;

    @ThriftField(1)
    public String message;

    @ThriftField(value = 2, idlAnnotations = {
            @ThriftIdlAnnotation(key = "testkey1", value = "testvalue1"),
            @ThriftIdlAnnotation(key = "testkey2", value = "testvalue2")})
    public int getType()
    {
        return type;
    }

    @ThriftField(value = 2, idlAnnotations = {
            @ThriftIdlAnnotation(key = "testkey1", value = "testvalue1"),
            @ThriftIdlAnnotation(key = "testkey2", value = "testvalue2")})
    public void setType(int type)
    {
        this.type = type;
    }
}
