package com.stable.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dangdang.ddframe.job.api.ShardingContext;
import com.dangdang.ddframe.job.api.simple.SimpleJob;
import com.stable.service.TickDataService;

import lombok.extern.log4j.Log4j2;

/**
 * 每周执行的任务
 * 
 */
@Component
@Log4j2
public class EveryWeekMonJobTickData implements SimpleJob {

	@Autowired
	private TickDataService tickDataService;

	public void execute(ShardingContext sc) {
		log.info("5.每周tick data 剩余fetch");
		tickDataService.fetch("", "", "0");
	}
}
