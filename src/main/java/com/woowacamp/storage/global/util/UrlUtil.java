package com.woowacamp.storage.global.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

// @Component
public class UrlUtil {
	public static String serverDomain = "localhost:8080";

	@Value("${share.server-domain}")
	private String injectedServerDomain;

	// @PostConstruct
	public void init() {
		serverDomain = injectedServerDomain;
	}

	public static String getAbsoluteUrl(String relativeUrl) {
		return serverDomain + relativeUrl;
	}
}
