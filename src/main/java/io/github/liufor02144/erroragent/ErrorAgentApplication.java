package io.github.liufor02144.erroragent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@MapperScan("io.github.liufor02144.erroragent.mapper")
public class ErrorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErrorAgentApplication.class, args);
    }
}
