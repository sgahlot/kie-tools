/*
 * Copyright 2010 JBoss, a divison Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.bus.server.io;

import org.jboss.errai.bus.client.protocols.MessageParts;
import org.jboss.errai.common.client.protocols.SerializationParts;
import org.jboss.errai.common.client.types.DecodingContext;
import org.jboss.errai.common.client.types.EncodingContext;
import org.jboss.errai.common.client.types.TypeHandler;
import org.mvel2.MVEL;

import javax.sound.sampled.AudioFormat;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.util.*;

import static org.jboss.errai.common.client.protocols.SerializationParts.ENCODED_TYPE;
import static org.jboss.errai.common.client.protocols.SerializationParts.ENUM_STRING_VALUE;
import static org.jboss.errai.common.client.protocols.SerializationParts.OBJECT_ID;

/**
 * Encodes an object into a JSON string
 */
public class JSONEncoder {
  protected static Set<Class> serializableTypes;

  public static void setSerializableTypes(Set<Class> serializableTypes) {
    JSONEncoder.serializableTypes = serializableTypes;
  }

  public static String encode(Object v) {
    return _encode(v, new EncodingContext());
  }

  private static String _encode(Object v, EncodingContext ctx) {
    if (v == null) {
      return "null";
    }
    else if (v instanceof String) {
      return encodeString((String) v, ctx);
    }
    if (v instanceof Number || v instanceof Boolean) {
      return String.valueOf(v);
    }
    else if (v instanceof Collection) {
      return encodeCollection((Collection) v, ctx);
    }
    else if (v instanceof Map) {
      //noinspection unchecked
      return encodeMap((Map) v, ctx);
    }
    else if (v.getClass().isArray()) {
      return encodeArray(v, ctx);

      // CDI Integration: Loading entities after the service was initialized
      // This may cause the client to throw an exception if the entity is not known
      // TODO: Improve exception handling for these cases

    }/* else if (serializableTypes.contains(v.getClass()) || tHandlers.containsKey(v.getClass())) {
            return encodeObject(v);
        } else {
            throw new RuntimeException("cannot serialize type: " + v.getClass().getName());
        }  */
    else if (v instanceof Enum) {
      return encodeEnum((Enum) v, ctx);
    }
    else {
      return encodeObject(v, ctx);
    }
  }

  private static String encodeObject(Object o, EncodingContext ctx) {
    if (o == null) return "null";

    Class cls = o.getClass();

    if (java.util.Date.class.isAssignableFrom(cls)) {
      return map(
              encodeCommaSeparatedStrings(ctx,
                      keyValue(encodeString(ENCODED_TYPE, ctx), encodeString(java.util.Date.class.getName(), ctx)),
                      keyValue(encodeString(OBJECT_ID, ctx), encodeString(String.valueOf(o.hashCode()), ctx)),
                      keyValue(encodeString(MessageParts.Value.name(), ctx), String.valueOf(((java.util.Date) o)
                              .getTime()
                      ))),
              ctx);

    }
    if (java.sql.Date.class.isAssignableFrom(cls)) {
      return map(
              encodeCommaSeparatedStrings(ctx,
                      keyValue(encodeString(ENCODED_TYPE, ctx), encodeString(java.sql.Date.class.getName(), ctx)),
                      keyValue(encodeString(OBJECT_ID, ctx), encodeString(String.valueOf(o.hashCode()), ctx)),
                      keyValue(encodeString(MessageParts.Value.name(), ctx), String.valueOf(((java.sql.Date) o)
                              .getTime()
                      ))),
              ctx);
    }

    if (tHandlers.containsKey(cls)) {
      return _encode(convert(o), ctx);
    }

    if (ctx.isEncoded(o)) {
      /**
       * If this object is referencing a duplicate object in the graph, we only provide an ID reference.
       */

      return map(encodeCommaSeparatedStrings(ctx,
              keyValue(encodeString(ENCODED_TYPE, ctx), encodeString(cls.getCanonicalName(), ctx)),
              keyValue(encodeString(OBJECT_ID, ctx), objRef(ctx, o))), ctx);
    }

    ctx.markEncoded(o);

    StringBuilder build = new StringBuilder("{" + encodeCommaSeparatedStrings(ctx,
            keyValue(encodeString(ENCODED_TYPE, ctx), encodeString(cls.getCanonicalName(), ctx)),
            keyValue(encodeString(OBJECT_ID, ctx), encodeString(String.valueOf(o.hashCode()),
                    ctx))));

    // Preliminary fix for https://jira.jboss.org/browse/ERRAI-103
    // TODO: Review my Mike
    final Field[] fields = EncodingUtil.getAllEncodingFields(cls);
    final Serializable[] s = EncodingCache.get(fields, new EncodingCache.ValueProvider<Serializable[]>() {
      public Serializable[] get() {
        Serializable[] s = new Serializable[fields.length];
        int i = 0;
        for (Field f : fields) {
          if ((f.getModifiers() & (Modifier.TRANSIENT | Modifier.STATIC)) != 0
                  || f.isSynthetic()) {
            continue;
          }
          s[i++] = MVEL.compileExpression(f.getName());
        }
        return s;
      }
    });

    int i = 0;
    boolean first = true;

    for (Field field : fields) {
      if ((field.getModifiers() & (Modifier.TRANSIENT | Modifier.STATIC)) != 0
              || field.isSynthetic()) {
        continue;
      }
      else if (first) {
        build.append(',');
        first = true;
      }

      try {
        Object v = MVEL.executeExpression(s[i++], o);
        build.append(encodeString(field.getName(), ctx)).append(':').append(_encode(v, ctx));

      }
      catch (Throwable t) {
        System.out.println("failed at encoding: " + field.getName());
        t.printStackTrace();
      }
    }

    return build.append('}').toString();
  }

  private static String encodeMap(Map<Object, Object> map, EncodingContext ctx) {
    StringBuilder mapBuild = new StringBuilder("{");
    boolean first = true;

    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      String val = _encode(entry.getValue(), ctx);
      if (!first) {
        mapBuild.append(',');
      }

      if (!(entry.getKey() instanceof String)) {
        mapBuild.append(write(ctx, '\"'));
        if (!ctx.isEscapeMode()) {
          mapBuild.append(SerializationParts.EMBEDDED_JSON);
        }

        ctx.setEscapeMode();
        mapBuild.append(_encode(entry.getKey(), ctx));
        ctx.unsetEscapeMode();
        mapBuild.append(write(ctx, '\"'));
        mapBuild.append(':')
                .append(val);

      }
      else {
        mapBuild.append(_encode(entry.getKey(), ctx))
                .append(':').append(val);
      }

      first = false;
    }

    return mapBuild.append('}').toString();
  }

  private static String map(String elements, EncodingContext ctx) {
    return "{" + elements + "}";
  }

  private static String objRef(EncodingContext ctx, Object o) {
    return encodeString("$" + ctx.markRef(o), ctx);
  }

  private static String keyValue(String key, String value) {
    return key + ":" + value;
  }

  private static String encodeCommaSeparatedStrings(EncodingContext ctx, String... strings) {
    boolean first = true;

    StringBuilder build = new StringBuilder();

    for (String s : strings) {
      if (!first) {
        build.append(',');
      }
      first = false;

      build.append(s);
    }

    return build.toString();
  }

  private static String encodeString(String string, EncodingContext ctx) {
    String quotes = write(ctx, '\"');
    return quotes + string.replaceAll("\\\"", "\\\\\"") + quotes;
  }

  private static String encodeCollection(Collection col, EncodingContext ctx) {
    StringBuilder buildCol = new StringBuilder("[");
    Iterator iter = col.iterator();
    while (iter.hasNext()) {
      buildCol.append(_encode(iter.next(), ctx));
      if (iter.hasNext()) buildCol.append(',');
    }
    return buildCol.append(']').toString();
  }

  private static String encodeArray(Object array, EncodingContext ctx) {
    StringBuilder buildCol = new StringBuilder("[");

    int len = Array.getLength(array);
    for (int i = 0; i < len; i++) {
      buildCol.append(_encode(Array.get(array, i), ctx));
      if ((i + 1) < len) buildCol.append(',');
    }

    return buildCol.append(']').toString();
  }

  private static String encodeEnum(Enum enumer, EncodingContext ctx) {
    return map(encodeCommaSeparatedStrings(ctx,
            keyValue(encodeString(ENCODED_TYPE, ctx), encodeString(enumer.getClass().getName(), ctx)),
            keyValue(encodeString(ENUM_STRING_VALUE, ctx), encodeString(enumer.name(), ctx))), ctx);
  }

  private static final Map<Class, TypeHandler> tHandlers = new HashMap<Class, TypeHandler>();

  static {
    tHandlers.put(Timestamp.class, new TypeHandler<Timestamp, Long>() {
      public Long getConverted(Timestamp in, DecodingContext ctx) {
        return in.getTime();
      }
    });

    tHandlers.put(Character.class, new TypeHandler<Character, String>() {
      public String getConverted(Character in, DecodingContext ctx) {
        return String.valueOf(in.charValue());
      }
    });
  }

  private static String write(EncodingContext ctx, String s) {
    if (ctx.isEscapeMode()) {
      return s.replaceAll("\"", "\\\\\"");
    }
    else {
      return s;
    }
  }

  private static String write(EncodingContext ctx, char s) {
    if (ctx.isEscapeMode() && s == '\"') {
      return "\\" + "\"";
    }
    else {
      return String.valueOf(s);
    }
  }

  public static void addEncodingHandler(Class from, TypeHandler handler) {
    tHandlers.put(from, handler);
  }

  private static final DecodingContext STATIC_DEC_CONTEXT = new DecodingContext();

  private static Object convert(Object in) {
    if (in == null || !tHandlers.containsKey(in.getClass())) return in;
    else {
      //noinspection unchecked
      return tHandlers.get(in.getClass()).getConverted(in, STATIC_DEC_CONTEXT);
    }
  }
}
