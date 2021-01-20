package com.stable.service.model;

import java.util.Comparator;
import java.util.List;

import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.stable.constant.Constant;
import com.stable.constant.EsQueryPageUtil;
import com.stable.service.ChipsService;
import com.stable.service.CodePoolService;
import com.stable.service.DaliyBasicHistroyService;
import com.stable.service.DaliyTradeHistroyService;
import com.stable.service.PriceLifeService;
import com.stable.service.StockBasicService;
import com.stable.service.TradeCalService;
import com.stable.service.model.data.AvgService;
import com.stable.service.model.data.LineAvgPrice;
import com.stable.service.model.data.LinePrice;
import com.stable.utils.CurrencyUitl;
import com.stable.utils.DateUtil;
import com.stable.utils.WxPushUtil;
import com.stable.vo.bus.CodePool;
import com.stable.vo.bus.StockAvgBase;
import com.stable.vo.bus.TradeHistInfoDaliy;
import com.stable.vo.bus.TradeHistInfoDaliyNofq;
import com.stable.vo.bus.ZengFa;
import com.stable.vo.spi.req.EsQueryPageReq;

import lombok.extern.log4j.Log4j2;

/**
 * 基本面趋势票
 *
 */
@Service
@Log4j2
public class CoodPoolModelService {
	@Autowired
	private PriceLifeService priceLifeService;
	@Autowired
	private CodeModelService codeModelService;
	@Autowired
	private CodePoolService codePoolService;
	@Autowired
	private DaliyTradeHistroyService daliyTradeHistroyService;
	@Autowired
	private AvgService avgService;
	@Autowired
	private TradeCalService tradeCalService;
	@Autowired
	private StockBasicService stockBasicService;
	@Autowired
	private ChipsService chipsService;
	@Autowired
	private DaliyBasicHistroyService daliyBasicHistroyService;

	private String OK = "基本面OK,疑是建仓";

//	private String NOT_OK = "系统默认NOT_OK";

	private double chkdouble = 80.0;// 10跌倒5.x

	public synchronized void start(int tradeDate, List<CodePool> list) {
		LineAvgPrice avg = new LineAvgPrice(avgService);
		log.info("code coop list:" + list.size());
		StringBuffer msg = new StringBuffer();
		StringBuffer mid = new StringBuffer();
		StringBuffer msg2 = new StringBuffer();
		StringBuffer msg3 = new StringBuffer();
		StringBuffer msg4 = new StringBuffer();
		if (list.size() > 0) {
			LinePrice lp = new LinePrice(daliyTradeHistroyService);
			for (CodePool m : list) {
				if (m.getMonitor() == 1) {
					m.setMonitor(0);// TODO
				}
				String code = m.getCode();
				boolean onlineYear = stockBasicService.online1YearChk(code, tradeDate);
				if (!onlineYear) {
					log.info("{},Online 上市不足1年", code);
					continue;
				}
				boolean isBigBoss = false;
				if (m.isIsok()) {
					// 1年整幅未超过80%
					if (lp.priceCheckForMid(code, m.getUpdateDate(), chkdouble)) {
						isBigBoss = true;
					}
				}
				// 是否大牛
				if (isBigBoss) {
					if (m.getSuspectBigBoss() == 0) {
						msg.append(code).append(",");
					}
					m.setRemark(OK);
					m.setSuspectBigBoss(1);
				} else {
					if (m.getSuspectBigBoss() == 1) {
						msg2.append(code).append(",");
					}
					m.setSuspectBigBoss(0);
				}

				// 是否中线(60日线)
				if (m.getPe_ttm() > 0 && priceLifeService.getLastIndex(code) >= 80
						&& avg.isWhiteHorseForMidV2(code, tradeDate)) {
					if (m.getInmid() == 0) {
						mid.append(code).append(",");
					}
					m.setInmid(1);
				} else {
					m.setInmid(0);
				}
				zfmoni(m, lp, tradeDate, msg4);
				// 1大牛，2中线，3人工，4短线 // 箱体新高（3个月新高，短期有8%的涨幅）
				//chk(m, code, tradeDate, msg3);TODO
				//大牛需要涨停

			}
			codePoolService.saveAll(list);
			if (msg.length() > 0 || mid.length() > 0) {
				if (msg.length() > 0) {
					msg.insert(0, "新发现疑似主力建仓票:");
				}
				if (mid.length() > 0) {
					mid.insert(0, "新发现中线票:");
				}
				msg.append(mid.toString());
				WxPushUtil.pushSystem1(msg.toString());
			}
			if (msg2.length() > 0) {
				WxPushUtil.pushSystem1("踢出主力建仓票:" + msg2.toString());
			}
			if (msg3.length() > 0) {
				WxPushUtil.pushSystem1("股票池-疑似-启动股票:" + msg3.toString());
			}
			if (msg4.length() > 0) {
				WxPushUtil.pushSystem1("增发完成-未启动股票:" + msg4.toString());
			}
		}
	}

	private void zfmoni(CodePool m, LinePrice lp, int tradeDate, StringBuffer msg4) {
		if (m.getZfStatus() == 2) {
			m.setInzf(1);
			m.setZfself(0);

			String code = m.getCode();
			ZengFa zf = chipsService.getLastZengFa(code);
			// 20个交易日股价
			List<TradeHistInfoDaliy> befor45 = daliyTradeHistroyService.queryListByCodeWithLastQfq(code, 0,
					zf.getEndDate(), EsQueryPageUtil.queryPage60, SortOrder.DESC);
			double end = befor45.get(0).getClosed();
			double max = befor45.stream().max(Comparator.comparingDouble(TradeHistInfoDaliy::getClosed)).get()
					.getClosed();
			if (max > end) {
				if (CurrencyUitl.cutProfit(end, max) >= 15.0) {
					m.setZfself(1);// 增发前跌幅在20%以上
				}
			}
			boolean preCondi = false;
			if (zf.getPrice() > 0) {
				// 价格对比,增发价没超60%
				double chkline = CurrencyUitl.topPriceN(zf.getPrice(), 1.5);
				double close = daliyBasicHistroyService.queryLastest(code).getClose();
				if (close <= chkline) {
					preCondi = true;
				}
			} else {
				// 没有价格对比就看一年涨幅
				if (lp.priceCheckForMid(code, tradeDate, chkdouble)) {
					preCondi = true;
				}
			}
			if (preCondi && m.getZfself() == 1) {
				if (m.getZfmoni() == 0) {
					if (m.getInzf() == 0) {
						msg4.append(code + ",增发价格:" + zf.getPrice()).append(Constant.HTML_LINE);
					}
				}
				m.setZfmoni(1);
			} else {
				m.setZfmoni(0);
			}
		} else {
			m.setInzf(0);
			m.setZfself(0);
			m.setZfmoni(0);
		}
	}

	private void chk(CodePool m, String code, int treadeDate, StringBuffer msg3) {
		// 1大牛，2中线，3人工，4短线,5增发
		if ((m.getMonitor() == 1 || m.getMonitor() == 3 || m.getMonitor() == 5) && isTodayPriceOk(code, treadeDate)
				&& isWhiteHorseForSortV5(code, treadeDate)) {
			msg3.append(code).append(",");
		}
	}

	/**
	 * 1.短期有9.5%的涨幅
	 */
	private boolean isTodayPriceOk(String code, int date) {
		// 3个月新高，22*3=60
		List<TradeHistInfoDaliyNofq> l2 = daliyTradeHistroyService.queryListByCodeWithLastNofq(code, 0, date,
				EsQueryPageUtil.queryPage30, SortOrder.DESC);
		for (TradeHistInfoDaliyNofq r : l2) {
			if (r.getTodayChangeRate() >= 9.5) {
				return true;
			}
		}
		log.info("{} 最近30个工作日无大涨交易", code);
		return false;
	}

	/**
	 * 2.均线
	 */
	private boolean isWhiteHorseForSortV5(String code, int date) {
		EsQueryPageReq req = EsQueryPageUtil.queryPage10;
		List<StockAvgBase> clist30 = avgService.queryListByCodeForModelWithLast60(code, date, req, true);
		int c = 0;
		for (StockAvgBase sa : clist30) {
			if (sa.getAvgPriceIndex30() >= sa.getAvgPriceIndex60()
					&& sa.getAvgPriceIndex20() >= sa.getAvgPriceIndex30()) {
				c++;
			}
		}
		if (c >= 8) {
			return true;
		}
		return false;
	}

	public synchronized void startManul() {
		int date = DateUtil.getTodayIntYYYYMMDD();
		date = tradeCalService.getPretradeDate(date);
		this.start(date, codeModelService.findBigBoss(date));
	}
}