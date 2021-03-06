package com.earth2me.mcperf.config;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigHandler {
    private static final Level LOG_LEVEL = Level.FINER;

    private final Logger logger;

    public ConfigHandler(Logger logger) {
        this.logger = logger;
    }

    public void apply(FileConfiguration config, Configurable obj) {
        Stream.Builder<Class<?>> typesBuilder = Stream.builder();
        for (Class<?> type = obj.getClass(); type != null; type = type.getSuperclass()) {
            typesBuilder.accept(type);
        }

        Field[] fields = Stream.concat(typesBuilder.build(), Arrays.stream(obj.getClass().getInterfaces()))
                .distinct()
                .filter(Configurable.class::isAssignableFrom)
                .flatMap(t -> Arrays.stream(t.getDeclaredFields()))
                .filter(f -> f.isAnnotationPresent(ConfigSetting.class))
                .toArray(Field[]::new);

        logger.log(LOG_LEVEL, String.format("Configuration settings found for %s: %d", obj.getId(), fields.length));

        String prefix = obj.getConfigPath() + '.';
        for (Field f : fields) {
            update(config, obj, prefix, f);
        }

        obj.onConfig(config);
    }

    private void update(FileConfiguration config, Configurable obj, String prefix, Field field) {
        String name = field.getName();
        String key = prefix + name;
        if (!config.contains(key)) {
            logger.log(LOG_LEVEL, String.format("Configuration doesn't contain key: %s", key));
            return;
        }

        Type genericType = field.getGenericType();
        String fullType = genericType.getTypeName();
        String type, generic;
        int openBracket = fullType.indexOf('<');
        if (openBracket < 0) {
            type = fullType;
            generic = "";
        } else {
            type = fullType.substring(0, openBracket);
            generic = fullType.substring(openBracket + 1, fullType.lastIndexOf('>'));
        }

        Class<?> genericClass = null;
        if (!generic.isEmpty()) {
            try {
                genericClass = Class.forName(generic);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(String.format("Unexpected setting generic parameter type %s derived from %s for config setting %s; class could not be found", generic, fullType, key), e);
            }
        }

        // Despite the name, this must not use generics because Java is stupid.
        Class genericEnumClass = genericClass != null && genericClass.isEnum() ? genericClass : null;

        Object value;

        switch (type) {
            case "boolean":
            case "java.lang.Boolean":
                value = config.getBoolean(key);
                break;

            case "int":
            case "java.lang.Integer":
                value = config.getInt(key);
                break;

            case "long":
            case "java.lang.Long":
                value = config.getLong(key);
                break;

            case "double":
            case "java.lang.Double":
                value = config.getDouble(key);
                break;

            case "java.lang.String":
                value = config.getString(key);
                break;

            case "java.util.List":
            case "java.util.Set":
            case "java.util.HashSet":
            case "java.util.EnumSet":
                List<?> list;
                EnumSet enumSet = null;
                switch (generic) {
                    case "java.lang.String":
                        list = config.getStringList(key);
                        break;

                    case "java.lang.Integer":
                        list = config.getIntegerList(key);
                        break;

                    case "java.lang.Short":
                        list = config.getShortList(key);
                        break;

                    case "java.lang.Byte":
                        list = config.getByteList(key);
                        break;

                    case "java.lang.Long":
                        list = config.getLongList(key);
                        break;

                    case "java.lang.Boolean":
                        list = config.getBooleanList(key);
                        break;

                    case "java.lang.Character":
                        list = config.getCharacterList(key);
                        break;

                    case "java.lang.Float":
                        list = config.getFloatList(key);
                        break;

                    case "java.lang.Double":
                        list = config.getDoubleList(key);
                        break;

                    case "java.util.Map":
                        list = config.getMapList(key);
                        break;

                    case "java.util.UUID": {
                        List<String> strings = config.getStringList(key);
                        list = strings.stream().map(UUID::fromString).collect(Collectors.toList());
                        break;
                    }

                    default:
                        if (genericEnumClass != null) {
                            List<String> strings = config.getStringList(key);
                            @SuppressWarnings("unchecked")
                            List enumList = (List) strings.stream().<Enum<?>>map(k -> Enum.valueOf(genericEnumClass, k)).collect(Collectors.toList());
                            list = enumList;

                            try {
                                enumSet = EnumSet.copyOf(enumList);
                            } catch (Exception e) {
                                enumSet = null;
                            }
                        } else {
                            throw new RuntimeException(String.format("Unexpected setting generic parameter type %s derived from %s for config setting %s", generic, fullType, key));
                        }
                        break;
                }

                switch (type) {
                    case "java.util.Set":
                        if (genericEnumClass == null) {
                            value = new HashSet<>(list);
                        } else {
                            value = enumSet == null ? EnumSet.noneOf(genericEnumClass) : enumSet;
                        }
                        break;

                    case "java.util.EnumSet":
                        if (genericEnumClass == null) {
                            throw new RuntimeException(String.format("Unexpected setting generic parameter type %s derived from %s for config setting %s; must extend Enum<?>", generic, fullType, key));
                        }
                        value = enumSet == null ? EnumSet.noneOf(genericEnumClass) : enumSet;
                        break;

                    case "java.util.HashSet":
                        value = new HashSet<>(list);
                        break;

                    case "java.util.List":
                        value = list;
                        break;

                    default:
                        throw new RuntimeException(String.format("Illogical control flow for config setting: %s", key));
                }
                break;

            case "org.bukkit.inventory.ItemStack":
                value = config.getItemStack(key);
                break;

            case "org.bukkit.OfflinePlayer":
                value = config.getOfflinePlayer(key);
                break;

            case "org.bukkit.util.Vector":
                value = config.getVector(key);
                break;

            case "java.util.UUID": {
                String uuid = config.getString(key);
                value = uuid == null ? null : UUID.fromString(uuid);
                break;
            }

            case "int[]":
                value = config.getIntegerList(key).stream().mapToInt(x -> x).toArray();
                break;

            case "double[]":
                value = config.getDoubleList(key).stream().mapToDouble(x -> x).toArray();
                break;

            case "long[]":
                value = config.getLongList(key).stream().mapToLong(x -> x).toArray();
                break;

            case "java.lang.String[]":
                value = config.getStringList(key).stream().toArray(String[]::new);
                break;

            default:
                throw new RuntimeException(String.format("Unexpected setting type: %s derived from %s for config setting %s", type, fullType, key));
        }

        if (value instanceof Map) {
            logger.log(LOG_LEVEL, String.format("Configuration: %s = {%s}", key, value.getClass().getSimpleName()));
        } else if (value instanceof List || value instanceof Set) {
            logger.log(LOG_LEVEL, String.format("Configuration: %s = {%s}", key, String.join(", ", ((Collection<?>) value).stream().map(Object::toString).toArray(String[]::new))));
        } else if (value instanceof OfflinePlayer) {
            logger.log(LOG_LEVEL, String.format("Configuration: %s = {Player:%s}", key, ((OfflinePlayer) value).getName()));
        } else if (value instanceof String) {
            logger.log(LOG_LEVEL, String.format("Configuration: %s = \"%s\"", key, value));
        } else {
            logger.log(LOG_LEVEL, String.format("Configuration: %s = %s", key, value == null ? "{null}" : value.toString()));
        }

        String methodName;
        if (name.length() > 1) {
            methodName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        } else {
            methodName = "set" + name.toUpperCase();
        }
        try {
            obj.getClass().getMethod(methodName, field.getType()).invoke(obj, value);
            return;
        } catch (NoSuchMethodException e) {
            logger.log(Level.WARNING, String.format("No setter found for signature: void %s(%s) in %s", methodName, field.getType().getName(), obj.getId()));
        } catch (InvocationTargetException | IllegalAccessException e) {
            logger.log(Level.SEVERE, String.format("Failed to set configuration value for %s via setter.", key), e);
            return;
        }

        // No setter.

        boolean accessible = field.isAccessible();
        if (!accessible) {
            field.setAccessible(true);
        }
        try {
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            logger.log(Level.SEVERE, String.format("Unable to set configuration value for %s via field.", key), e);
        } finally {
            if (!accessible) {
                field.setAccessible(false);
            }
        }
    }
}
