package com.stable.spider.eastmoney;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.stable.es.dao.base.DzjyDao;
import com.stable.service.StockBasicService;
import com.stable.utils.DateUtil;
import com.stable.utils.ErrorLogFileUitl;
import com.stable.utils.HttpUtil;
import com.stable.utils.ThreadsUtil;
import com.stable.utils.WxPushUtil;
import com.stable.vo.bus.Dzjy;
import com.stable.vo.bus.StockBaseInfo;

import lombok.extern.log4j.Log4j2;

@Component
@Log4j2
public class EmDzjySpider {

	// http://data.eastmoney.com/dxf/q/601989.html

	private static final String J_QUERY112306735504837667934_1610722345186 = "jQuery112300768465300321155_1615975430563(";
	private static String URL_S = "http://dcfm.eastmoney.com/em_mutisvcexpandinterface/api/js/get?callback=jQuery112300768465300321155_1615975430563&st=TDATE&sr=-1&ps=50&p=1&js=%7Bpages%3A(tp)%2Cdata%3A(x)%7D&token=70f12f2f4f091e459a279469fe49eca5&type=DZJYXQ&filter=(SECUCODE%3D%27";
	private static String URL_E = "%27)";

	@Autowired
	private StockBasicService stockBasicService;
	@Autowired
	private DzjyDao dzjyDao;

	public void byJob() {
		dofetchInner();
	}

	public void byWeb() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				dofetchInner();
			}
		}).start();
	}

	private void dofetchInner() {
		try {
			int date = DateUtil.formatYYYYMMDDReturnInt(DateUtil.addDate(new Date(), -1));
			List<Dzjy> dzl = new LinkedList<Dzjy>();
			List<StockBaseInfo> codelist = stockBasicService.getAllOnStatusListWithOutSort();
			int c = 0;
			for (StockBaseInfo s : codelist) {
				try {
					dofetch(s.getCode(), dzl);
				} catch (Exception e) {
					ErrorLogFileUitl.writeError(e, "", "", "");
				}
				c++;
				log.info("current index:{}", c);

				if (dzl.size() > 100) {
					dzjyDao.saveAll(dzl);
					dzl = new LinkedList<Dzjy>();
				}
			}
			if (dzl.size() > 0) {
				dzjyDao.saveAll(dzl);
			}
			WxPushUtil.pushSystem1(date + " 东方财富大宗交易-已完成");
		} catch (Exception e) {
			e.printStackTrace();
			ErrorLogFileUitl.writeError(e, "东方财富大宗交易-运行异常..", "", "");
			WxPushUtil.pushSystem1("东方财富大宗交易-运行异常");
		}
	}

	public void dofetch(String code, List<Dzjy> list) {
		int trytime = 0;
		do {
			trytime++;
			try {
				String url = URL_S + code + URL_E;
				log.info(url);
				String result = HttpUtil.doGet2(url);
				result = result.substring(J_QUERY112306735504837667934_1610722345186.length(), result.length() - 1);
				JSONObject object = JSON.parseObject(result);
				JSONArray objects = object.getJSONArray("data");
				for (int i = 0; i < objects.size(); i++) {
//						private String TDATE;
//						private String SECUCODE;
//						private String SNAME;
//						private String PRICE;
//						private String TVOL;
//						private String TVAL;
//						private String BUYERNAME;
//						private String SALESNAME;
//						private String RCHANGE;
					try {
						Dzjy dzjy = new Dzjy();
						JSONObject data = objects.getJSONObject(i);
						dzjy.setSECUCODE(data.getString("SECUCODE"));
//						dzjy.setSNAME(data.getString("SNAME"));
						String date = data.getString("TDATE").substring(0, 10);
						// System.err.println(date);
						dzjy.setDate(DateUtil.convertDate2(date));
						dzjy.setPRICE(Double.valueOf(data.getString("PRICE")));
						dzjy.setTVOL(Double.valueOf(data.getString("TVOL")));
						dzjy.setTVAL(Double.valueOf(data.getString("TVAL")));
						dzjy.setBUYERNAME(data.getString("BUYERNAME"));
						dzjy.setSALESNAME(data.getString("SALESNAME"));
						dzjy.setRCHANGE(Double.valueOf(data.getString("RCHANGE")));
						dzjy.setId();
						log.info(dzjy);
						list.add(dzjy);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				return;
			} catch (Exception e) {
				ThreadsUtil.sleepRandomSecBetween15And30(trytime);
				e.printStackTrace();
			}
		} while (trytime <= 10);
		WxPushUtil.pushSystem1("东方财富-大宗交易-抓包出错,code=" + code);
	}

	public static void main(String[] args) {
		EmDzjySpider es = new EmDzjySpider();
		String[] as = { "601989" };
		for (int i = 0; i < as.length; i++) {
			es.dofetch(as[i], null);
		}
	}

}
