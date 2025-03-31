package com.woowacamp.storage.global.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.redisson.Redisson;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.connection.ConnectionListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@NoArgsConstructor
public class RedissonManager {
	private final List<RedissonClient> redissonClients = new ArrayList<>();
	private RedissonClient currentClient;
	private boolean[] connectedClient;
	private long[] lastTime;
	private Map<RedissonClient, Integer> map;


	@Value("${spring.redisson.nodes}")
	private String[] redisNodes;

	private static final int CLIENT_CHECK_TIME = 5*1000;
	private static final int LOCK_TTL = 10 * 1000;

	@PostConstruct
	public void init() throws IOException {
		connectedClient = new boolean[redisNodes.length];
		lastTime = new long[redisNodes.length];
		map = new HashMap<>();

		int idx = 0;
		for (String node : redisNodes) {
			int i = idx;
			InputStream configStream = getClass().getClassLoader().getResourceAsStream("redisson.yml");
			Config config = Config.fromYAML(configStream);
			config.useSingleServer().setAddress(node);
			config.setConnectionListener(new ConnectionListener() {
				@Override
				public void onConnect(InetSocketAddress addr) {
					log.info("[REDIS CONNECTED] address = {}, port = {}", addr.getAddress(), addr.getPort());
					connectedClient[i] = true;
				}

				@Override
				public void onDisconnect(InetSocketAddress addr) {
					log.info("[REDIS DISCONNECTED] address = {}, port = {}", addr.getAddress(), addr.getPort());
					// handleDisconnection();
					connectedClient[i] = false;
					lastTime[i] = System.currentTimeMillis();
				}
			});
			RedissonClient redissonClient = Redisson.create(config);

			redissonClients.add(redissonClient);
			map.put(redissonClient, idx++);
		}

		currentClient = redissonClients.get(0);
	}

	private synchronized void changeRedissonClient() {
		log.info("[HANDLE DISCONNECTION START]");
		for (RedissonClient redissonClient : redissonClients) {
			if (isConnected(redissonClient)) {
				log.info("[NEW REDIS CONNECTED] {}", redissonClient.getId());
				currentClient = redissonClient;
				break;
			}
		}
	}

	private boolean isConnected(RedissonClient client) {
		try {
			log.info("[IS CONNECTED TEST]");
			for (int i = 0; i <= 3; i++) {
				String result = pingWithLuaScriptAsync(client).get().toString();
				if (result.equals("PONG")) {
					connectedClient[i] = true;
					return true;
				}
			}
			return false;
		} catch (Exception e) {
			log.error("[HEALTH CHECK ERROR], {}",e);
			return false;
		}
	}

	private RFuture<Object> pingWithLuaScriptAsync(RedissonClient client) {
		String script = "return redis.call('PING')";

		return client.getScript().evalAsync(RScript.Mode.READ_ONLY, script, RScript.ReturnType.VALUE);
	}

	public RedissonClient getCurrentClient() {
		return currentClient;
	}

	@Scheduled(fixedDelay = CLIENT_CHECK_TIME)
	private void checkCurrentClient() {
		Integer idx = map.get(currentClient);
		long now = System.currentTimeMillis();
		if (connectedClient[idx]) {
			return;
		}

		if (now - lastTime[idx] < LOCK_TTL) {
			return;
		}

		changeRedissonClient();
	}
}
