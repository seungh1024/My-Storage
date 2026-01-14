package com.woowacamp.storage.lock.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DistributedLock {
	String keys();

	long waitTime() default 3L;

	long leaseTime() default -1L; // -1 -> watchdog

	TimeUnit unit() default TimeUnit.SECONDS;
}
