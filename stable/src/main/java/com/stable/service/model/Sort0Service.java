package com.stable.service.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.stable.constant.EsQueryPageUtil;
import com.stable.service.DaliyTradeHistroyService;
import com.stable.utils.CurrencyUitl;
import com.stable.utils.DateUtil;
import com.stable.vo.bus.CodeBaseModel2;
import com.stable.vo.bus.KlineAttack;
import com.stable.vo.bus.TradeHistInfoDaliy;

/**
 * 底部W形态,攻击形态
 */
@Service
public class Sort0Service {
	@Autowired
	private DaliyTradeHistroyService daliyTradeHistroyService;

	public void attackAndW(CodeBaseModel2 newOne, int date) {
		if (attackAndW(newOne.getCode(), date)) {
			newOne.setShootingw(1);
		} else {
			newOne.setShootingw(0);
		}
	}

	/**
	 * 底部W形态,攻击形态
	 */
	private boolean attackAndW(String code, int date) {
		List<TradeHistInfoDaliy> l120t = daliyTradeHistroyService.queryListByCodeWithLastQfq(code, 0, date,
				EsQueryPageUtil.queryPage120, SortOrder.DESC);
		int lastDate = l120t.get(l120t.size() - 1).getDate();
		int day = lastDate % 100;// 某月某日
		int startDate = 0;
		if (day <= 20) {
			startDate = Integer.valueOf((lastDate / 100) + "01");// 本月yyyy/mm/01
		} else {
			startDate = Integer.valueOf((DateUtil.addMonth(lastDate, 1) / 100) + "01");// 上月yyyy/mm+1/01
		}
		List<TradeHistInfoDaliy> l120 = daliyTradeHistroyService.queryListByCodeQfq(code, startDate, 0,
				EsQueryPageUtil.queryPage500, SortOrder.DESC);

		double maxPrice = l120.stream().max(Comparator.comparingDouble(TradeHistInfoDaliy::getClosed)).get()
				.getClosed();
		double minPrice = l120.stream().min(Comparator.comparingDouble(TradeHistInfoDaliy::getClosed)).get()
				.getClosed();
		double profit = CurrencyUitl.cutProfit(minPrice, maxPrice);
		if (profit >= 65) {// 半年之间整幅超过65%中间去掉(收盘价)
			return false;
		}

		Map<Integer, KlineAttack> map = new HashMap<Integer, KlineAttack>();
		// 按月区分最高最低
		for (TradeHistInfoDaliy r : l120) {
			int month = r.getDate() / 100;// 月份
			KlineAttack ka = map.get(month);
			if (ka == null) {
				ka = new KlineAttack();
				ka.setMonth(month);
				map.put(month, ka);
			}
			ka.addHigh(r.getHigh());
			ka.addLow(r.getLow());
			if (CurrencyUitl.cutProfit(r.getLow(), r.getHigh()) > 50) {// 当月整幅超过50%,放弃？
				return false;
			}
		}
		// 每月振幅
		List<KlineAttack> rl = new LinkedList<KlineAttack>();
		for (int month : map.keySet()) {
			KlineAttack ka = map.get(month);
			rl.add(ka);
		}
		// 排序
		Collections.sort(rl, new Comparator<KlineAttack>() {
			@Override
			public int compare(KlineAttack o1, KlineAttack o2) {
				if (o1.getMonth() == o2.getMonth()) {
					return 0;
				}
				return o2.getMonth() - o1.getMonth() > 0 ? 1 : -1;
			}
		});
//		System.err.println("=======================");
//		for (KlineAttack ka : rl) {
//			System.err.println(ka);
//		}
//		System.err.println("=======================");
		// 条件1：总体向上
		KlineAttack curr = rl.get(0);
		KlineAttack last = rl.get(rl.size() - 1);
		if (curr.getHigh() > last.getHigh() && curr.getLow() > last.getLow()) {
			// 当前月的最高和最低都高于
			return true;
		}
		KlineAttack pre1 = rl.get(1);
		if (pre1.getHigh() > last.getHigh() && pre1.getLow() > last.getLow()) {
			// 除开当前月，上月的最高和最低都高于
			return true;
		}

		// 条件2:近3个月月底部上移
		double cl = curr.getLow();
		boolean isOk = true;
		for (int i = 1; i < 4; i++) {
			if (cl < rl.get(i).getLow()) {
				isOk = false;
			}
			cl = rl.get(i).getLow();
		}
		return isOk;
	}

//	@javax.annotation.PostConstruct
//	private void a() {
//		new Thread(new Runnable() {
//			@Override
//			public void run() {
//				System.err.println("result=" + attackAndW("601019", 20220512));
//
//			}
//		}).start();
//	}
}
