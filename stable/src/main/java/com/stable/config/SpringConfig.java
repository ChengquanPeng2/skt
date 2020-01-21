package com.stable.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Configuration
@Data
public class SpringConfig {

	@Value("${error.log.file}")
	private String filepath = "/my/free/error.log";

	@Value("${no.tickdata.log.folder}")
	private String notickdata = "/my/free/notickdata/";
	
	@Value("${wx.push.app.token.system}")
	private String appToken;
	
	@Value("${wx.push.myuid}")
	private String myUid;
}