package com.discovergy.apiclient;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.github.scribejava.core.builder.api.DefaultApi10a;
import com.github.scribejava.core.model.OAuth1RequestToken;

@Singleton
public class DiscovergyApi extends DefaultApi10a {

	private String baseAddress;
	@Inject
	@ConfigProperty(name = "discovergy.email")
	String user;
	@Inject
	@ConfigProperty(name = "discovergy.password")
	String password;

	public DiscovergyApi() {
		super();
	}
	
	@PostConstruct
	void init() {
		baseAddress = "https://api.discovergy.com/public/v1";
	}

	public String getBaseAddress() {
		return baseAddress;
	}

	public String getUser() {
		return user;
	}

	@Override
	public String getRequestTokenEndpoint() {
		return baseAddress + "/oauth1/request_token";
	}

	@Override
	public String getAccessTokenEndpoint() {
		return baseAddress + "/oauth1/access_token";
	}

	@Override
	public String getAuthorizationUrl(OAuth1RequestToken requestToken) {
		try {
			return baseAddress + "/oauth1/authorize?oauth_token=" + requestToken.getToken() + "&email=" + URLEncoder.encode(user, UTF_8.name()) + "&password="
					+ URLEncoder.encode(password, UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}
}
