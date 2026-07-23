package com.ticketbooking.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    // @Lazy is load-bearing, not an optimization: Redisson connects to Redis
    // eagerly when the client is constructed. Only the redis-lock and
    // redis-counter strategies ever inject RedissonClient (both gated by
    // @ConditionalOnProperty), so every other strategy's Spring context --
    // including most of this project's tests, none of which start a Redis
    // container -- must never trigger that connection attempt. Without
    // @Lazy, this bean would be constructed unconditionally on every
    // context startup and fail to connect wherever Redis isn't running.
    @Lazy
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + redisHost + ":" + redisPort);
        return Redisson.create(config);
    }
}
