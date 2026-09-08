package network.vonix.guardian.core.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;

/**
 * Gson 2.8.x-compatible adapter for Java records.
 *
 * <p>Minecraft 1.18.2 supplies Gson 2.8.9 ahead of nested mod libraries. Its
 * reflective adapter allocates records with Unsafe and then tries to write final
 * components, which fails on Java 17. This factory uses the canonical record
 * constructor instead, preserving record immutability and constructor defaults.
 */
public final class RecordTypeAdapterFactory implements TypeAdapterFactory {
    public static final RecordTypeAdapterFactory INSTANCE = new RecordTypeAdapterFactory();

    private RecordTypeAdapterFactory() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<?> raw = type.getRawType();
        if (!raw.isRecord()) return null;
        return (TypeAdapter<T>) new RecordAdapter(gson, raw).nullSafe();
    }

    private static final class RecordAdapter<T> extends TypeAdapter<T> {
        private final Gson gson;
        private final Constructor<T> constructor;
        private final RecordComponent[] components;
        private final TypeAdapter<?>[] delegates;
        private final Method[] accessors;

        @SuppressWarnings("unchecked")
        private RecordAdapter(Gson gson, Class<? super T> raw) {
            this.gson = gson;
            this.components = raw.getRecordComponents();
            this.delegates = new TypeAdapter<?>[components.length];
            this.accessors = new Method[components.length];
            Class<?>[] parameterTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                Type genericType = component.getGenericType();
                delegates[i] = gson.getAdapter(TypeToken.get(genericType));
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                accessors[i] = accessor;
            }
            try {
                constructor = (Constructor<T>) raw.getDeclaredConstructor(parameterTypes);
                constructor.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Record lacks canonical constructor: " + raw.getName(), e);
            }
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            JsonObject object = new JsonObject();
            for (int i = 0; i < components.length; i++) {
                try {
                    Object componentValue = accessors[i].invoke(value);
                    @SuppressWarnings("unchecked")
                    TypeAdapter<Object> delegate = (TypeAdapter<Object>) delegates[i];
                    object.add(components[i].getName(), delegate.toJsonTree(componentValue));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new JsonParseException("Could not read record component " + components[i].getName(), e);
                }
            }
            gson.toJson(object, out);
        }

        @Override
        public T read(JsonReader in) throws IOException {
            JsonElement tree = new JsonParser().parse(in);
            if (tree == null || tree.isJsonNull()) return null;
            if (!tree.isJsonObject()) {
                throw new JsonParseException("Expected JSON object for record");
            }
            JsonObject object = tree.getAsJsonObject();
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                JsonElement child = object.get(component.getName());
                if (child == null || child instanceof JsonNull || child.isJsonNull()) {
                    values[i] = primitiveDefault(component.getType());
                    continue;
                }
                @SuppressWarnings("unchecked")
                TypeAdapter<Object> delegate = (TypeAdapter<Object>) delegates[i];
                values[i] = delegate.fromJsonTree(child);
            }
            try {
                return constructor.newInstance(values);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new JsonParseException("Record constructor rejected JSON", cause);
            } catch (ReflectiveOperationException e) {
                throw new JsonParseException("Could not construct record", e);
            }
        }

        private static Object primitiveDefault(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            return null;
        }
    }
}
