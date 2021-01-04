package com.stable.vo.http.resp;

import com.stable.vo.bus.CodePool;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CodePoolResp extends CodePool {

	private String codeName;
	private String incomeShow;
	private String profitShow;
	private String yjlx;
	private String monitorDesc;
	private String sort6Desc;
	private String sort7Desc;
}
