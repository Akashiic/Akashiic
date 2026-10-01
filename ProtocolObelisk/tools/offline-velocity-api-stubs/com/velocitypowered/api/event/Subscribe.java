package com.velocitypowered.api.event;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
    short priority() default 0;
    boolean async() default true;
}
