package com.stable.service.monitor;

import java.util.Date;
import java.util.List;

import com.stable.constant.Constant;
import com.stable.service.ConceptService;
import com.stable.service.model.prd.msg.MsgPushServer;
import com.stable.spider.realtime.RealTime;
import com.stable.spider.realtime.RealtimeCall;
import com.stable.utils.CurrencyUitl;
import com.stable.utils.DateUtil;
import com.stable.utils.MonitoringUitl;
import com.stable.utils.TagUtil;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class RealtimeDetailsAnalyzer implements Runnable {
	private ConceptService conceptService;
	public static final long ONE_MIN = 1 * 60 * 1000;// 5MIN
	private static final long FIVE_MIN = 5 * 60 * 1000;// 5MIN
	private static final long TEN_MIN = 10 * 60 * 1000;// 5MIN
	private long WAIT_MIN = FIVE_MIN;
	public String code;
	private String codeName;
	private boolean isRunning = true;
	private String today = DateUtil.getTodayYYYYMMDD();
	RtmMoniGbl rtm;
	private boolean chkCodeClosed = false;
	private double yearHigh1;

	private boolean burstPointCheckTopPrew = false;// 突破前1%
	private boolean burstPointCheckTop = false;// 突破
	private boolean burstPointCheckSzx = false;// 十字星

	public void stop() {
		isRunning = false;
	}

	private String getUsers(List<RtmMoniUser> listu) {
		StringBuffer sb = new StringBuffer();
		for (RtmMoniUser r : listu) {
			sb.append(r.getUser().getId()).append(",");
		}
		return sb.toString();
	}

	public int init(String code, RtmMoniGbl rtm, String codeName, double yh, ConceptService c) {
		log.info(code + ":" + getUsers(rtm.getListu()));
		this.code = code;
		this.codeName = codeName;
		this.rtm = rtm;
		RealTime srt = RealtimeCall.get(code);
		if (srt.getOpen() == 0.0 && srt.getBuy1() == 0.0 && srt.getSell1() == 0.0) {
			log.info("{}  SINA 今日疑似停牌或者可能没有集合竞价", codeName);
			chkCodeClosed = true;
		}
		this.yearHigh1 = yh;
		this.conceptService = c;
		return 1;
	}

	private String getBaseInfo() {
		return Constant.HTML_LINE + Constant.HTML_LINE + "行业/概念：" + TagUtil.getGn(conceptService.getCodeConcept(code))
				+ Constant.HTML_LINE + Constant.HTML_LINE + "基础信息：" + rtm.getBase().getZfjjInfo()// -
				+ Constant.HTML_LINE + Constant.HTML_LINE + "避雷区：" + rtm.getBase().getBaseInfo() // -
				+ Constant.HTML_LINE + Constant.HTML_LINE + "其他：" + rtm.getBase().getTagInfo();
	}

	public void run() {
		boolean isQibao = (rtm.getBizPushService() != null);
		RealTime srt = null;
		if (chkCodeClosed) {// 重新检测停牌
			try {
				Thread.sleep(TEN_MIN);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			srt = RealtimeCall.get(code);
			if (srt.getOpen() == 0.0) {
				log.info("SINA 今日停牌,{}", codeName);
				for (RtmMoniUser r : rtm.getListu()) {
					MsgPushServer.pushSystemT1(codeName + "今日停牌", rtm.getMsg(r.getOrig()), r.getUser());
				}
				return;
			}
		}
		long d1130 = DateUtil.getTodayYYYYMMDDHHMMSS_NOspit(
				DateUtil.parseDate(today + "113000", DateUtil.YYYY_MM_DD_HH_MM_SS_NO_SPIT));
		Date date = DateUtil.parseDate(today + "130100", DateUtil.YYYY_MM_DD_HH_MM_SS_NO_SPIT);
		long d1300 = DateUtil.getTodayYYYYMMDDHHMMSS_NOspit(date);
		// long d1450 = DateUtil.getTodayYYYYMMDDHHMMSS_NOspit(
		// DateUtil.parseDate(today + "145000", DateUtil.YYYY_MM_DD_HH_MM_SS_NO_SPIT));
		if (isQibao) {
			WAIT_MIN = ONE_MIN;// 起爆一分钟一次
			if (srt == null) {
				srt = RealtimeCall.get(code);
			}
			double today = CurrencyUitl.topPrice(srt.getYesterday(), false);// 今天涨停价格
			if (today < rtm.getOrig().getShotPointPrice()) {
				double wp4 = CurrencyUitl.roundHalfUp((rtm.getOrig().getShotPointPrice() * 0.94));// 起爆点的-4%
				if (wp4 <= today && today < rtm.getOrig().getShotPointPrice()) {// 涨停价+2%触及起爆点时，就提前4%预警
					rtm.warningYellow = wp4;
				}
			}
		}
		String title = null;
		while (isRunning) {
			try {
				long now = DateUtil.getTodayYYYYMMDDHHMMSS_NOspit(new Date());
				if (d1130 <= now && now <= d1300) {
					long from3 = new Date().getTime();
					int millis = (int) ((date.getTime() - from3));
					log.info("{},中场休息。", code);
					if (millis > 0) {
						Thread.sleep(millis);
					}
				}

				RealTime rt = RealtimeCall.get(code);

				// 起爆点
				if (isQibao) {
					if (!burstPointCheckTop && rtm.getOrig().getShotPointPrice() > 0) {
						if (rt.getHigh() >= rtm.getOrig().getShotPointPrice()) {
							burstPointCheckTop = rtm.bizPushService.PushS2(
									codeName + rtm.you + "[7]突破买点:" + rtm.getOrig().getShotPointPrice(), getBaseInfo());
						} else if (!burstPointCheckTopPrew && rt.getHigh() >= rtm.warningYellow) {
							burstPointCheckTopPrew = rtm.bizPushService.PushS2(codeName + rtm.you + "[7]准备突破买点:"
									+ rtm.getOrig().getShotPointPrice() + "现价:" + rtm.warningYellow, getBaseInfo());
						}
					}
					if (!burstPointCheckSzx && rtm.getOrig().getShotPointPriceSzx() > 0
							&& rt.getHigh() >= rtm.getOrig().getShotPointPriceSzx()) {
						burstPointCheckSzx = rtm.bizPushService.PushS2(
								codeName + rtm.you + " [10]突破买点:" + rtm.getOrig().getShotPointPriceSzx(),
								getBaseInfo());
					}

//					if (!burstPointCheckLow && qibao.getOrig().getShotPointPriceLow() <= rt.getLow()
//							&& rt.getLow() <= qibao.getOrig().getShotPointPriceLow5()) {
//						burstPointCheckLow = true;
//						qibao.bizPushService
//								.PushS2(codeName + " 接近旗形底部买点[:" + qibao.getOrig().getShotPointPriceLow() + "-"
//										+ qibao.getOrig().getShotPointPriceLow5() + "]");
//					}
				}

				for (RtmMoniUser r : rtm.getListu()) {
					title = "";
					// 正常价格监听
					boolean isOk = MonitoringUitl.isOkForRt(r.getOrig(), rt);
					if (isOk) {
						if (r.waitSend) {
							String st = MonitoringUitl.okMsg(r.getOrig(), rt);
							title = st;
							r.waitSend = false;
						}
					}
					// 一年新高
					if (rt.getHigh() > yearHigh1 && !r.highPriceGot && yearHigh1 > 0) {
						if ("".equals(title)) {
							title = " 一年新高!";
						} else {
							title += "一年新高! ";
						}
						r.highPriceGot = true;
					}

					// 发送
					if (!title.equals("")) {
						MsgPushServer.pushSystemT1(codeName + " " + title, rtm.getMsg(r.getOrig()), r.getUser());
					}
				}

				Thread.sleep(WAIT_MIN);
			} catch (Exception e) {
				if (!isPushedException) {
					MsgPushServer.pushSystemT1(code + " 监听异常！", "");
					isPushedException = true;
					e.printStackTrace();
				}
				try {
					Thread.sleep(FIVE_MIN);
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
			}
		}
	}

	private boolean isPushedException = false;
}
