package com.stable.spider.ths;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.SearchQuery;
import org.springframework.stereotype.Component;

import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.stable.es.dao.base.EsCodeConceptDao;
import com.stable.es.dao.base.EsConceptDailyDao;
import com.stable.es.dao.base.EsConceptDao;
import com.stable.service.TradeCalService;
import com.stable.utils.DateUtil;
import com.stable.utils.HtmlunitSpider;
import com.stable.utils.ThreadsUtil;
import com.stable.utils.WxPushUtil;
import com.stable.vo.bus.CodeConcept;
import com.stable.vo.bus.Concept;
import com.stable.vo.bus.ConceptDaily;
import com.stable.vo.spi.req.EsQueryPageReq;

import lombok.extern.log4j.Log4j2;

@Component
@Log4j2
public class ThsSpider {
	@Autowired
	private TradeCalService tradeCalService;
	@Autowired
	private EsConceptDao esConceptDao;
	@Autowired
	private EsCodeConceptDao esCodeConceptDao;
	@Autowired
	private EsConceptDailyDao esConceptDailyDao;
	@Autowired
	private HtmlunitSpider htmlunitSpider;

	// private static final String FIN_RPT_URL =
	// "http://basic.10jqka.com.cn/%s/finance.html";

	private String GN_LIST = "http://q.10jqka.com.cn/gn/index/field/addtime/order/desc/page/%s/ajax/1/";
	private static int ths = 1;
	private static String START_THS = "THS";
	private static String SPIT = "/";
	private static Map<String, Concept> allmap = new HashMap<String, Concept>();
	static {
		for (int i = 0; i < ConAll.all.length; i++) {
			String[] string = ConAll.all[i].split(",");
			String url = string[0];
			String name = string[1];
			List<String> ids = Arrays.asList(url.split(SPIT));
			Concept cp = new Concept();
			cp.setCode(ids.get(ids.size() - 1));
			cp.setId(START_THS + cp.getCode());
			cp.setType(ths);
			cp.setDate(20100101);
			cp.setHref(url);
			cp.setName(name);
			allmap.put(cp.getCode(), cp);
		}
	}

//	private String host = "http://127.0.0.1:8081";
//	private String host = "http://106.52.95.147:9999";
//	private String url0 = host + "/web/concept/allConcepts";
//	private String url1 = host + "/web/concept/addConcept";
//	private String url2 = host + "/web/concept/addCodeConcept";
//	private String url3 = host + "/web/concept/addConceptDaily";

	private void saveConceptDaily(List<ConceptDaily> list) {
		if (list.size() > 0) {
			esConceptDailyDao.saveAll(list);
			list.clear();
		}
	}

	private void saveConcept(List<Concept> list) {
		if (list.size() > 0) {
			esConceptDao.saveAll(list);
			list.clear();
		}
	}

	EsQueryPageReq querypage = new EsQueryPageReq(1000);

	private void deleteCodeConcept(Concept cp) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		if (StringUtils.isNotBlank(cp.getId())) {
			bqb.must(QueryBuilders.matchPhraseQuery("conceptId", cp.getId()));
			NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
			Pageable pageable = PageRequest.of(querypage.getPageNum(), querypage.getPageSize());
			SearchQuery sq = queryBuilder.withQuery(bqb).withPageable(pageable).build();
			Page<CodeConcept> page = esCodeConceptDao.search(sq);
			if (page != null && !page.isEmpty()) {
				List<CodeConcept> list = page.getContent();
				esCodeConceptDao.deleteAll(list);
				log.info("CodeConcept delete size:{}", list.size());
			} else {
				log.info("no records CodeConcept");
			}
		} else {
			throw new RuntimeException("cp.getId is null");
		}
	}

	private void saveCodeConcept(List<CodeConcept> list) {
		if (list.size() > 0) {
			esCodeConceptDao.saveAll(list);
			list.clear();
		}
	}

	private Map<String, Concept> getAllAliasCode() {
		Map<String, Concept> m = new HashMap<String, Concept>();
		esConceptDao.findAll().forEach(x -> {
			if (StringUtils.isNotBlank(x.getAliasCode()) && !"null".equals(x.getAliasCode())) {
				m.put(x.getCode(), x);
			}
		});
		log.info(m.size());
		return m;
	}

	public void start() {
		int date = Integer.valueOf(DateUtil.getTodayYYYYMMDD());
		if (!tradeCalService.isOpen(date)) {
			log.info("非交易日");
			return;
		}
		Map<String, Concept> m = synchGnAndCode();
		if (m == null) {
			try {
				Thread.sleep(Duration.ofMinutes(5).toMillis());
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			m = getAllAliasCode();
		}
		synchConceptDaliy(date, m);
	}

	private void synchConceptDaliy(int date, Map<String, Concept> m) {
		List<ConceptDaily> list = new LinkedList<ConceptDaily>();
		try {
			List<String> keys = new ArrayList<String>(m.keySet());
			for (int i = 0; i < keys.size(); i++) {
				int trytime = 0;
				Concept cp = m.get(keys.get(i));
				log.info("抓包：" + cp.getName());
				if ("THS301531".equals(cp.getId())) {
					log.info("跳过首发新股");
					// 首发新股
					continue;
				}
				boolean fetched = false;
				do {
					ThreadsUtil.sleepRandomSecBetween5And15();
					HtmlPage page = null;
					try {
						log.info(cp.getHref());
						page = htmlunitSpider.getHtmlPageFromUrl(cp.getHref());
						HtmlElement body = page.getBody();
						HtmlElement boardInfos = body.getElementsByAttribute("div", "class", "board-infos").get(0);
						Iterator<DomElement> it = boardInfos.getChildElements().iterator();
						ConceptDaily cd = new ConceptDaily();
						cd.setOpen(Double.valueOf(it.next().getLastElementChild().asText()));// 今开
						cd.setYesterday(Double.valueOf(it.next().getLastElementChild().asText()));// 昨收
						cd.setLow(Double.valueOf(it.next().getLastElementChild().asText()));// 最低
						cd.setHigh(Double.valueOf(it.next().getLastElementChild().asText()));// 最高
						cd.setVol(Double.valueOf(it.next().getLastElementChild().asText()));// 成交量(万手)
						cd.setTodayChange(Double.valueOf(it.next().getLastElementChild().asText().replace("%", "")));// 板块涨幅
						cd.setRanking(Integer.valueOf(it.next().getLastElementChild().asText().split(SPIT)[0]));// 涨幅排名
						cd.setUpdownNum(it.next().getLastElementChild().asText());// 涨跌家数
						cd.setInComeMoney(Double.valueOf(it.next().getLastElementChild().asText()));// 资金净流入(亿)
						cd.setAmt(Double.valueOf(it.next().getLastElementChild().asText()));// 成交额(亿)
						Iterator<DomElement> it2 = body.getElementsByAttribute("div", "class", "board-hq").get(0)
								.getChildElements().iterator();
						it2.next();
						cd.setClose(Double.valueOf(it2.next().asText()));// 收盘
						fetched = true;
						cd.setConceptId(cp.getId());
						cd.setDate(date);
						cd.setId();
						log.info(cd);
						list.add(cd);
					} catch (Exception e2) {
						e2.printStackTrace();
						trytime++;
						ThreadsUtil.sleepRandomSecBetween15And30(trytime);
						if (trytime >= 10) {
							fetched = true;
							e2.printStackTrace();
							WxPushUtil.pushSystem1("同花顺概念-每日交易出错," + cp.getName() + ",url=" + cp.getHref());
						}
					} finally {
						htmlunitSpider.close();
					}
				} while (!fetched);
			}
			int cnt = list.size();
			saveConceptDaily(list);
			WxPushUtil.pushSystem1("同花顺板块交易记录同步成功,需求抓取总是[" + keys.size() + "],实际成功总数:[" + cnt + "]");
		} catch (Exception e) {
			saveConceptDaily(list);
			e.printStackTrace();
			WxPushUtil.pushSystem1("同花顺概念-每日交易出错 end");
			throw new RuntimeException(e);
		}
	}

	private Map<String, Concept> synchGnAndCode() {
		Date today = new Date();
		Calendar c = Calendar.getInstance();
		c.setTime(today);
		int weekday = c.get(Calendar.DAY_OF_WEEK);
		boolean isFirday = true;
		if (weekday != 6) {
			log.info("今日非周五");
			isFirday = false;
		}
		Map<String, Concept> map = null;
		try {
			map = getAllAliasCode();
			getGnList(isFirday, map);
		} catch (Exception e) {
			e.printStackTrace();
			WxPushUtil.pushSystem1("同花顺板块出错");
			map = null;
		}
		return map;
	}

	public void getGnList(boolean isFirday, Map<String, Concept> map) {
		List<Concept> list = new LinkedList<Concept>();
		List<CodeConcept> codelist = new LinkedList<CodeConcept>();
		int cntList = 0;
		int cntCodelist = 0;
		int limit = 1;
		if (isFirday) {
			limit = 3;
		}

		int index = 1;
		int end = 0;
		int trytime = 0;
		do {
			String url = String.format(GN_LIST, index);
			DomElement table = null;
			HtmlPage page = null;
			try {
				ThreadsUtil.sleepRandomSecBetween5And15();
//				header.put(REFERER, refer);
				page = htmlunitSpider.getHtmlPageFromUrl(url);
				table = page.getBody().getFirstElementChild();

				DomElement tbody = table.getLastElementChild();
				Iterator<DomElement> trs = tbody.getChildElements().iterator();
				while (trs.hasNext()) {
					Iterator<DomElement> tr = trs.next().getChildElements().iterator();
					Concept cp = new Concept();
					cp.setDate(Integer.valueOf(
							DateUtil.formatYYYYMMDD(DateUtil.parseDate(tr.next().asText(), DateUtil.YYYY_MM_DD2))));
					DomElement td2 = tr.next();
					cp.setName(td2.asText());// name
					String href = td2.getFirstElementChild().getAttribute("href");
					cp.setHref(href);
					tr.next();// 驱动事件
					tr.next();// 龙头股
					try {
						cp.setCnt(Integer.valueOf(tr.next().asText()));// 成分股数量
					} catch (Exception e) {
						e.printStackTrace();
					}
					List<String> ids = Arrays.asList(cp.getHref().split(SPIT));
					cp.setCode(ids.get(ids.size() - 1));
					cp.setId(START_THS + cp.getCode());
					cp.setType(ths);
					list.add(cp);
					log.info(cp);
					boolean fetchNext = true;

					if (!isFirday) {// 周一到周4只需要获取新概念，周五就重新获取概率下所有股票
						// 每天新增
						if (map.containsKey(cp.getCode())) {
							fetchNext = false;
						} else {
							log.info("获取到新概念:" + cp.getName());
							fetchNext = true;
						}
					}
					if (fetchNext) {
						getAliasCdoe(cp, map);
						getSubCodeList(cp, codelist);
						deleteCodeConcept(cp);
						if (codelist.size() > 100) {
							cntCodelist += codelist.size();
							saveCodeConcept(codelist);
						}
						if (list.size() >= 100) {
							cntList += list.size();
							saveConcept(list);
						}
					}
				}
				index++;
				if (end == 0) {
					String pageInfo = page.getElementById("m-page").getLastElementChild().asText();
					end = Integer.valueOf(pageInfo.split(SPIT)[1]);
				}
				trytime = 0;
				if (index > limit) {// 最多3页
					break;
				}
			} catch (Exception e) {
				trytime++;
				ThreadsUtil.sleepRandomSecBetween15And30(trytime);
				if (trytime >= 10) {
					e.printStackTrace();
					WxPushUtil.pushSystem1("同花顺概念-列表抓包出错,url=" + url);
					if (list.size() > 0) {
						cntList += list.size();
						saveConcept(list);
					}
					if (codelist.size() > 0) {
						cntCodelist += codelist.size();
						saveCodeConcept(codelist);
					}
					throw new RuntimeException(e);
				}

			} finally {
				htmlunitSpider.close();
			}
		} while (index <= end);

		try {
			for (String id : allmap.keySet()) {
				log.info("!map.contains name={},? {}", allmap.get(id).getName(), (!map.containsKey(id)));
				if (!map.containsKey(id)) {
					Concept cp = allmap.get(id);
					getAliasCdoe(cp, map);
					getSubCodeList(cp, codelist);
					list.add(cp);
					log.info("获取到新概念:" + cp.getName());
				}
			}
		} finally {
			if (list.size() > 0) {
				cntList += list.size();
				saveConcept(list);
			}
			if (codelist.size() > 0) {
				cntCodelist += codelist.size();
				saveCodeConcept(codelist);
			}
		}

		WxPushUtil.pushSystem1("同花顺板块同步成功,同步概念[" + cntList + "],概念相关股票[" + cntCodelist + "]");
	}

	private String GN_CODE_LIST = "http://q.10jqka.com.cn/gn/detail/field/264648/order/desc/page/%s/ajax/1/code/%s";

	private void getAliasCdoe(Concept cp, Map<String, Concept> map) {
		if (map.containsKey(cp.getCode())) {
			cp.setAliasCode(map.get(cp.getCode()).getAliasCode());
			return;
		}
		ThreadsUtil.sleepRandomSecBetween5And15();

		HtmlPage page = null;
		try {
			page = htmlunitSpider.getHtmlPageFromUrl(cp.getHref());
			String aliasCode = page.getElementById("clid").getAttribute("value");
			log.info("{} get AliasCode code:{}", cp.getName(), aliasCode);
			if (StringUtils.isNotBlank(aliasCode) && !"null".equals(aliasCode)) {
				cp.setAliasCode(aliasCode);
				map.put(cp.getCode(), cp);
			}
		} catch (Exception e) {
			e.printStackTrace();
			WxPushUtil.pushSystem1("同花顺概念-成分股抓包出错,url=" + cp.getHref());
			throw new RuntimeException(e);
		} finally {
			htmlunitSpider.close();
		}
	}

	private void getSubCodeList(Concept cp, List<CodeConcept> codelist) {
		int updateTime = Integer.valueOf(DateUtil.getTodayYYYYMMDD());
		int index = 1;
		int end = 0;
		int trytime = 0;
		int stcnt = 0;
		do {
			ThreadsUtil.sleepRandomSecBetween5And15();

			String url = String.format(GN_CODE_LIST, index, cp.getCode());
			log.info(url);
			DomElement table = null;
			HtmlPage page = null;
			try {
//				header.put(REFERER, cp.getHref());
				page = htmlunitSpider.getHtmlPageFromUrl(url);
				table = page.getBody().getFirstElementChild();
				DomElement tbody = table.getLastElementChild();
				if (tbody.asText().contains("暂无成份股数据")) {
					return;
				}
				Iterator<DomElement> trs = tbody.getChildElements().iterator();
				while (trs.hasNext()) {
					Iterator<DomElement> tr = trs.next().getChildElements().iterator();
					tr.next();// 序号

					CodeConcept cc = new CodeConcept();
					cc.setUpdateTime(updateTime);
					cc.setCode(tr.next().asText());// code
					cc.setConceptId(cp.getId());
					cc.setConceptName(cp.getName());
					cc.setType(ths);
					cc.setId(cp.getId() + cc.getCode());
					codelist.add(cc);
					log.info(cc);
					stcnt++;
				}
				index++;
				if (cp.getCnt() > 10 && end == 0) {
					String pageInfo = page.getElementById("m-page").getLastElementChild().asText();
					end = Integer.valueOf(pageInfo.split(SPIT)[1]);
				}
				trytime = 0;
				cp.setCnt(stcnt);
			} catch (Exception e) {
				trytime++;
				ThreadsUtil.sleepRandomSecBetween15And30(trytime);
				if (trytime >= 10) {
					e.printStackTrace();
					log.info(page.asText());
					WxPushUtil.pushSystem1("同花顺概念-成分股抓包出错,url=" + url);
					throw new RuntimeException(e);
				}
			} finally {
				htmlunitSpider.close();
			}
		} while (index <= end);
	}
}
