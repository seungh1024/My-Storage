package com.woowacamp.storage.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.f4b6a3.tsid.TsidFactory;

@Configuration
public class TsidConfig {
	@Bean
	public TsidFactory tsidFactory(
		@Value("${tsid.nodeBits}") int nodeBits,
		@Value("${tsid.dc}") int dc,
		@Value("${tsid.worker}") int worker,
		@Value("${tsid.move}") int move
	) {
		// 상위 n비트는 dc, 하위 m비트는 worker 서버 고유번호
		int node = (dc << move) | worker;

		return TsidFactory.builder()
			.withNodeBits(nodeBits)
			.withNode(node)
			.build();
	}
}
