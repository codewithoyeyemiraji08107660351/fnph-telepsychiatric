package com.fnph.telepsychiatric;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
// Account emails are sent off the request thread. Creating a user account must
// not fail because a mail server timed out.
@EnableAsync
public class TelepsychiatricApplication {

	public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(TelepsychiatricApplication.class, args);
	}

}
