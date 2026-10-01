package com.velocitypowered.api.plugin;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Plugin {
    String id();
    String name() default "";
    String version() default "";
    String description() default "";
    String[] authors() default {};
}
