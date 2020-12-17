package com.stable.service.trace;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.stable.service.CodePoolService;
import com.stable.service.DaliyTradeHistroyService;
import com.stable.service.model.CodeModelService;
import com.stable.service.model.data.LinePrice;
import com.stable.utils.WxPushUtil;
import com.stable.vo.bus.CodePool;

import lombok.extern.log4j.Log4j2;

/**
 * 基本面趋势票
 *
 */
@Service
@Log4j2
public class MiddleSortV1Service {
	@Autowired
	private CodeModelService codeModelService;
	@Autowired
	private CodePoolService codePoolService;
	@Autowired
	private DaliyTradeHistroyService daliyTradeHistroyService;
//
	private String OK = "基本面OK,疑是建仓";
//	private String NOT_OK = "系统默认NOT_OK";

	private double chkdouble = 80.0;// 10跌倒5.x

	public synchronized void start(List<CodePool> list) {
		log.info("code coop list:" + list.size());
		StringBuffer msg = new StringBuffer();
		if (list.size() > 0) {
			LinePrice lp = new LinePrice(daliyTradeHistroyService);
			for (CodePool m : list) {
				if (m.getContinYj1() >= 3 || m.getContinYj2() >= 3) {
					// 1年整幅未超过80%
					if (lp.priceCheckForMid(m.getCode(), m.getUpdateDate(), chkdouble)) {
						if (m.getSuspectBigBoss() == 0) {
							msg.append(m.getCode()).append(",");
						}
						m.setSuspectBigBoss(1);
						m.setMidRemark(OK);
					}
				}
			}
			codePoolService.saveAll(list);
			if (msg.length() > 0) {
				WxPushUtil.pushSystem1("新发现疑似主力建仓票:" + msg.toString());
			}
		}
	}

	public synchronized void startManul() {
		List<CodePool> list = codeModelService.findBigBoss();
		for (CodePool m : list) {
			m.setSuspectBigBoss(0);
		}
		this.start(list);
	}
}
