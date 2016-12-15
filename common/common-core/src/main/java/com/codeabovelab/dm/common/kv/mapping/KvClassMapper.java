/*
 * Copyright 2016 Code Above Lab LLC
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

package com.codeabovelab.dm.common.kv.mapping;

import com.codeabovelab.dm.common.kv.*;
import com.codeabovelab.dm.common.validate.Validity;
import com.codeabovelab.dm.common.validate.ValidityException;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 */
@Slf4j
public class KvClassMapper<T> {

    private final Class<T> type;
    private final String prefix;
    private final KvMapperFactory factory;
    private final Map<Class, Map<String, KvProperty>> props = new HashMap<>();
    private final KeyValueStorage storage;

    KvClassMapper(KvMapperFactory factory, String prefix, Class<T> type) {
        this.factory = factory;
        this.prefix = prefix;
        this.type = type;
        Map<String, KvProperty> map = this.factory.loadProps(type, p -> p);
        this.props.put(type, map);
        this.storage = factory.getStorage();
    }

    public String getPrefix() {
        return prefix;
    }

    public void delete(String name) {
        this.storage.deletedir(path(name), DeleteDirOptions.builder().recursive(true).build());
    }

    private String path(String name) {
        return KvUtils.join(this.prefix, name);
    }

    /**
     * Save object to storage and return object that allow check for object modifications.
     * @param name
     * @param object
     * @return
     */
    public void save(String name, T object) {
        save(name, object, null);
    }

    void save(String name, T object, KvPropertySetCallback callback) {
        String path = path(name);
        // we must read index of all node but, we can not create node in single command,
        // so we use index of last sub node
        Collection<KvProperty> props = getProps(object);
        for(KvProperty property: props) {
            String strval = property.get(object);
            String proppath = KvUtils.join(path, property.getKey());
            try {
                KvNode res = this.storage.set(proppath, strval);
                if(callback != null) {
                    callback.call(property, res);
                }
            } catch (Exception e) {
                throw new RuntimeException("Error at path: " + proppath, e);
            }
        }
    }

    private Collection<KvProperty> getProps(T object) {
        Class<?> clazz = object.getClass();
        Map<String, KvProperty> p = this.props.get(clazz);
        if (p == null || CollectionUtils.isEmpty(this.props.get(clazz).values())) {
            Map<String, KvProperty> map = this.factory.loadProps(clazz, t ->  t);
            this.props.put(clazz, map);
            return map.values();
        } else {
            return this.props.get(clazz).values();
        }
    }

    /**
     * list same objects in storage
     * @return
     */
    public List<String> list() {
        List<String> list = this.storage.list(prefix);
        if (list == null) {
            createPrefix();
            list = this.storage.list(prefix);
        }
        return list.stream().map(k -> getName(k)).collect(Collectors.toList());
    }

    /**
     * Get relative name from full path.
     * @param path
     * @return
     */
    public String getName(String path) {
        return KvUtils.name(prefix, path);
    }

    private void createPrefix() {
        this.storage.setdir(prefix, WriteOptions.builder().failIfExists(true).build());
    }

    /**
     *
     * @param name can be obtainer from {@link #list()}
     * @return
     */
    public T load(String name) {
        String path = path(name);
        //check that mapped dir is exists
        KvNode node = this.storage.get(path);
        if(node == null) {
            return null;
        }
        Class<T> actualType = resolveType(path);
        T object = BeanUtils.instantiate(actualType);
        load(name, object);
        return object;
    }

    @SuppressWarnings("unchecked")
    private Class<T> resolveType(String path) {
        Class<T> actualType = this.type;
        JsonTypeInfo typeInfo = AnnotationUtils.findAnnotation(this.type, JsonTypeInfo.class);
        if (typeInfo != null) {
            String property = typeInfo.property();
            String proppath = KvUtils.join(path, property);
            try {
                String str = this.storage.get(proppath).getValue();
                JsonSubTypes subTypes = AnnotationUtils.findAnnotation(this.type, JsonSubTypes.class);
                for (JsonSubTypes.Type t : subTypes.value()) {
                    if (t.name().equals(str.replace("\"", ""))) {
                        actualType = (Class<T>) t.value();
                    }
                }
            } catch (Exception e) {
                log.error("can't instantiate class", e);
            }
        }
        return actualType;
    }

    /**
     * Load into existed object
     * @param name
     * @param object
     */
    public void load(String name, T object) {
        String path = path(name);
        for(KvProperty property: getProps(object)) {
            String proppath = KvUtils.join(path, property.getKey());
            String str;
            try {
                str = this.storage.get(proppath).getValue();
            } catch (Exception e) {
                throw new RuntimeException("Error at path: " + proppath, e);
            }
            property.set(object, str);
        }
        Validity validity = factory.validate(path, object);
        if(!validity.isValid()) {
            throw new ValidityException("Invalid : ", validity);
        }
    }
}
