package com.stable.service;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.SearchQuery;
import org.springframework.stereotype.Service;

import com.stable.constant.EsQueryPageUtil;
import com.stable.enums.RunCycleEnum;
import com.stable.enums.RunLogBizTypeEnum;
import com.stable.es.dao.base.EsFinYjkbDao;
import com.stable.es.dao.base.EsFinYjygDao;
import com.stable.es.dao.base.EsFinanceBaseInfoDao;
import com.stable.es.dao.base.EsFinanceBaseInfoHyDao;
import com.stable.job.MyCallable;
import com.stable.service.model.RunModelService;
import com.stable.service.model.data.FinanceAnalyzer;
import com.stable.service.model.prd.msg.MsgPushServer;
import com.stable.service.monitor.MonitorPoolService;
import com.stable.spider.eastmoney.EastmoneySpider;
import com.stable.spider.ths.ThsHolderSpider;
import com.stable.utils.BeanCopy;
import com.stable.utils.CurrencyUitl;
import com.stable.utils.DateUtil;
import com.stable.utils.ErrorLogFileUitl;
import com.stable.utils.TasksWorker;
import com.stable.utils.ThreadsUtil;
import com.stable.vo.WeekendFinFetchRtl;
import com.stable.vo.YgInfo;
import com.stable.vo.bus.CodeBaseModel2;
import com.stable.vo.bus.CodeConcept;
import com.stable.vo.bus.FinYjkb;
import com.stable.vo.bus.FinYjyg;
import com.stable.vo.bus.FinanceBaseInfo;
import com.stable.vo.bus.FinanceBaseInfoHangye;
import com.stable.vo.bus.FinanceBaseInfoPage;
import com.stable.vo.bus.StockBaseInfo;
import com.stable.vo.http.resp.FinanceBaseInfoResp;
import com.stable.vo.spi.req.EsQueryPageReq;

import lombok.extern.log4j.Log4j2;

/**
 * 财务
 *
 */
@Service
@Log4j2
public class FinanceService {

	@Autowired
	private EsFinanceBaseInfoDao esFinanceBaseInfoDao;
	@Autowired
	private EsFinanceBaseInfoHyDao esFinanceBaseInfoHyDao;
	@Autowired
	private StockBasicService stockBasicService;
	@Autowired
	private EsFinYjygDao esFinYjygDao;
	@Autowired
	private EsFinYjkbDao esFinYjkbDao;
	@Autowired
	private EastmoneySpider eastmoneySpider;
	@Autowired
	private RunModelService runModelService;
	@Autowired
	private ThsHolderSpider thsHolderSpider;
	@Autowired
	private ConceptService conceptService;
	@Autowired
	private MonitorPoolService monitorPoolService;

	/**
	 * 删除redis，从头开始获取
	 */
	public boolean spiderFinaceHistoryInfoFromStart(String code) {
//		List<FinanceBaseInfo> list = new LinkedList<FinanceBaseInfo>();
//		if (spiderFinaceHistoryInfo(code, list)) {
//			if (list.size() > 0) {
//				esFinanceBaseInfoDao.saveAll(list);
//			}
//			return true;
//		}
		// 东方财富限制，目前最多抓取5条,暂时够用，有需要在慢慢完善。
		return false;
	}

	private boolean spiderFinaceHistoryInfo(String code, List<FinanceBaseInfo> list, int companyType, int beforeChkDate,
			int index) {
		try {
			List<FinanceBaseInfoPage> datas = eastmoneySpider.getNewFinanceAnalysis(code, companyType, beforeChkDate);
			if (datas == null) {
				log.warn("{},上市未满1年，不需要从df抓取到Finane记录,code={}", index, code);
				// WxPushUtil.pushSystem1("未从东方财富抓取到Finane记录,code=" + code);
				return true;
			}
			if (datas.size() <= 0) {
				log.warn("{},未从df抓取到Finane记录,code={}", index, code);
				MsgPushServer.pushToSystem("未从东方财富抓取到Finane记录,code=" + code);
				return false;
			}
			// 东方财富限制，目前最多抓取5条
			log.warn("{},季度-从df抓取到Finane记录{}条,code={}", index, datas.size(), code);
			// 数据无误的则加入
			for (FinanceBaseInfoPage p : datas) {
				// 缺数据时可以放开这个条件。正常不能放开：因为资产负债表等只返回5条，错误数据会覆盖之前的正确数据
				if (p.isDataOk()) {
					FinanceBaseInfo f = new FinanceBaseInfo();
					BeanCopy.copy(p, f);
					list.add(f);
				}
			}
		} finally {
			ThreadsUtil.sleepRandomSecBetween1And5();
		}
		return true;
	}

	public List<FinanceBaseInfo> getFinaceReports(String code, String year, String quarter, EsQueryPageReq queryPage) {
		int pageNum = queryPage.getPageNum();
		int size = queryPage.getPageSize();
		log.info("queryPage code={},pageNum={},size={}", code, pageNum, size);
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		if (StringUtils.isNotBlank(code)) {
			bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		}
		if (StringUtils.isNotBlank(year)) {
			bqb.must(QueryBuilders.matchPhraseQuery("year", year));
		}
		if (StringUtils.isNotBlank(quarter)) {
			bqb.must(QueryBuilders.matchPhraseQuery("quarter", quarter));
		}
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();

		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		return null;
	}

	public List<FinanceBaseInfoResp> getListByCodeForWebPage(String code, String year, String quarter,
			EsQueryPageReq queryPage) {
		List<FinanceBaseInfoResp> res = new LinkedList<FinanceBaseInfoResp>();
		List<FinanceBaseInfo> list = this.getFinaceReports(code, year, quarter, queryPage);
		if (list != null) {
			for (FinanceBaseInfo dh : list) {
				FinanceBaseInfoResp resp = new FinanceBaseInfoResp();
				resp.setCode(dh.getCode());
				resp.setEndDate(String.valueOf(dh.getDate()));
				resp.setEndType(dh.getYear(), dh.getQuarter());
				resp.setCodeName(stockBasicService.getCodeName(dh.getCode()));
				resp.setYyzsr(getRedHtml(dh.getYyzsr()));
				resp.setGsjlr(getRedHtml(dh.getGsjlr()));
				resp.setKfjlr(getRedHtml(dh.getKfjlr()));
				resp.setYyzsrtbzz(CurrencyUitl.roundHalfUp(dh.getYyzsrtbzz()));
				resp.setGsjlrtbzz(CurrencyUitl.roundHalfUp(dh.getGsjlrtbzz()));
				resp.setKfjlrtbzz(CurrencyUitl.roundHalfUp(dh.getKfjlrtbzz()));
				resp.setJqjzcsyl(dh.getJqjzcsyl());
				resp.setMgjyxjl(dh.getMgjyxjl());
				resp.setMll(dh.getMll());
				resp.setJyxjl(getRedHtml2(dh.getJyxjlce()));
				resp.setAccountrec(CurrencyUitl.covertToString(dh.getAccountrec()));
				resp.setAccountPay(CurrencyUitl.covertToString(dh.getAccountPay()));
				resp.setSumLasset(CurrencyUitl.covertToString(dh.getSumLasset()));
				resp.setSumDebtLd(CurrencyUitl.covertToString(dh.getSumDebtLd()));
				resp.setNetAsset(getRedHtml2(dh.getNetAsset()));
				resp.setZcfzl(CurrencyUitl.roundHalfUp(dh.getZcfzl()));
				resp.setTotalAmt(getRedHtml2(dh.getMonetaryFund() + dh.getTradeFinassetNotfvtpl()));
				resp.setBorrow(CurrencyUitl.covertToString(dh.getStborrow() + dh.getLtborrow()));
				resp.setGoodWill(CurrencyUitl.covertToString(dh.getGoodWill()) + "+"
						+ CurrencyUitl.covertToString(dh.getIntangibleAsset()));
				res.add(resp);
			}
		}
		return res;
	}

	private String getRedHtml2(double v) {
		String s = CurrencyUitl.covertToString(v);
		if (v <= 0) {
			s = "<font color='red'>" + s + "</font>";
		}
		return s;
	}

	private String getRedHtml(long v) {
		String s = CurrencyUitl.covertToString(v);
		if (v < 0) {
			s = "<font color='red'>" + s + "</font>";
		}
		return s;
	}

	public FinanceBaseInfo getFinaceReportByLteDate(String code, int date) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("date").lte(date));// 报告期
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).build();

		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		log.info("no last report fince date={}", date);
		return null;
	}

	public List<FinanceBaseInfo> getFinacesReportByLteDate(String code, int date, EsQueryPageReq queryPage8) {
		Pageable pageable = PageRequest.of(queryPage8.getPageNum(), queryPage8.getPageSize());
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("date").lte(date));// 报告期
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();

		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		log.info("no last report fince code={}", code);
		return null;
	}

	public List<FinanceBaseInfo> getFinacesReportByYearRpt(String code, EsQueryPageReq queryPage8) {
		Pageable pageable = PageRequest.of(queryPage8.getPageNum(), queryPage8.getPageSize());
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.matchPhraseQuery("quarter", 4));// 报告期
		FieldSortBuilder sort = SortBuilders.fieldSort("year").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();

		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		log.info("no last report fince year code={}", code);
		return null;
	}

	public FinanceBaseInfo getFinaceReportByDate(String code, int year, int quarter) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.matchPhraseQuery("year", year));
		bqb.must(QueryBuilders.matchPhraseQuery("quarter", quarter));

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).build();

		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		log.info("no last report fince code={},year={},quarter={}", code, year, quarter);
		return null;

	}

	public FinanceBaseInfo getLastFinaceReport(String code, int annDate) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("annDate").lte(annDate));// 最新公告
		FieldSortBuilder sort = SortBuilders.fieldSort("annDate").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable1).build();

		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		log.info("no last report fince code={},annDate={}", code, annDate);
		return null;
	}

	public FinYjyg getLastFinYjygReport(String code, int annDate, int year, int jidu) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("annDate").lte(annDate));//
		bqb.must(QueryBuilders.matchPhraseQuery("year", year));
		bqb.must(QueryBuilders.matchPhraseQuery("quarter", jidu));
		bqb.must(QueryBuilders.matchPhraseQuery("isValid", 1));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).build();

		Page<FinYjyg> page = esFinYjygDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		log.info("no FinYjyg report fince code={},annDate={}", code, annDate);
		return null;
	}

	public FinYjkb getLastFinYjkbReport(String code, int annDate, int year, int jidu) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("annDate").lte(annDate));//
		bqb.must(QueryBuilders.matchPhraseQuery("year", year));
		bqb.must(QueryBuilders.matchPhraseQuery("quarter", jidu));
		bqb.must(QueryBuilders.matchPhraseQuery("isValid", 1));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).build();

		Page<FinYjkb> page = esFinYjkbDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		log.info("no FinYjkb report fince code={},annDate={}", code, annDate);
		return null;
	}

	Pageable pageable4 = PageRequest.of(0, 4);
	Pageable pageable1 = PageRequest.of(0, 1);

	/**
	 * 最近4个季度
	 */
	public List<FinanceBaseInfo> getLastFinaceReport4Quarter(String code) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable4).build();
		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
			// log.info("page size={},last report fince code={},date={}",
			// page.getContent().size(), code, f.getDate());
		}
		log.info("no last report fince code={},now!", code);
		return null;
	}

	public FinanceBaseInfo getLastFinaceReport(String code) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable1).build();

		Page<FinanceBaseInfo> page = esFinanceBaseInfoDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		log.info("no last report fince code={},now!", code);
		return null;
	}

	public FinYjkb getLastFinaceKbByReportDate(String code, int currYear, int currQuarter) {
		FinYjkb yjkb = getLastFinaceKbByReportDate(code);
		if (yjkb != null) {
			if ((currYear == yjkb.getYear() && currQuarter < yjkb.getQuarter())// 同一年，季度大于
					|| (yjkb.getYear() > currYear)) {// 不同年
//				newOne.setForestallIncomeTbzz(yjkb.getYyzsrtbzz());
//				newOne.setForestallProfitTbzz(yjkb.getJlrtbzz());
//				hasKb = true;
				return yjkb;
			}
		}
		return null;
	}

	public FinYjyg getLastFinaceYgByReportDate(String code, int currYear, int currQuarter) {
		FinYjyg yjyg = getLastFinaceYgByReportDate(code);
		if (yjyg != null) {
			if ((currYear == yjyg.getYear() && currQuarter < yjyg.getQuarter())// 同一年,季度大于
					|| (yjyg.getYear() > currYear)) {// 不同年
				return yjyg;
			}
		}
		return null;
	}

	public int finBigBoss(YgInfo yi, FinanceAnalyzer fa, List<FinanceBaseInfo> fbis, CodeBaseModel2 newOne) {
		// 最新的年报不能亏损
		if (fa.getCurrYear().getKfjlr() > 0) {
			// 首先看： 快报，预告，需要计算。然后人工校验
			if (yi != null && yi.getYgjlr() > 0 && yi.getYgjlrtbzz() > 0) {// 预告要增长
				// 需要计算
				long currKf = yi.getYgjlr();// 包含整个季度的
				// 非1季度
				if (yi.getQuarter() != 1) {// 第一季就是当季度的，不用减。234季度需要获取当前季度的
					FinanceBaseInfo preQutFin = fa.getCurrJidu();
					currKf = currKf - preQutFin.getKfjlr();

					// 2个季度至少1亿
					if (yi.getYgjlr() <= CurrencyUitl.YI_N.longValue()) {
						currKf = 0;
					}
				}
				if (currKf > 0) {
					// 上次单季度,// 上年的季度扣非不为0
					FinanceBaseInfo preYearFin = this.findPreFin(fbis, yi.getYear() - 1, yi.getQuarter());
					double rate = CurrencyUitl.cutProfit(Double.valueOf(preYearFin.getKfjlr()), Double.valueOf(currKf));
					if ((preYearFin.getKfjlr() > 0 && rate > 80) || rate >= 300) {// 往年季度不亏：80，亏：300
						newOne.setBossVal(CurrencyUitl.roundHalfUp(rate));
						int i = this.kfInc(fbis) + 1;
						newOne.setBossInc(i);
						return 2;
					}
				}
			}
			/** 业绩牛 */
			// 没有找到，则最新的财报重新计算
			if (fa.getCurrJidu().getDjdKfTbzz() > 80) {
				FinanceBaseInfo preYearFin = this.findPreFin(fbis, fa.getCurrJidu().getYear() - 1,
						fa.getCurrJidu().getQuarter());
				if (preYearFin.getKfjlr() > 0) {// 上年的季度扣非不为0

					// 2个季度至少1亿
					if (fa.getCurrJidu().getQuarter() == 1
							|| fa.getCurrJidu().getKfjlr() >= CurrencyUitl.YI_N.longValue()) {
						newOne.setBossVal(CurrencyUitl.roundHalfUp(fa.getCurrJidu().getDjdKfTbzz()));
						newOne.setBossInc(this.kfInc(fbis));
						return 1;
					}
				}
			}
		}
		return 0;
	}

	private int kfInc(List<FinanceBaseInfo> fbis) {
		int inc = 0;
		for (FinanceBaseInfo f : fbis) {
			if (f.getDjdKfTbzz() > 80) {
				inc++;
			} else {
				break;
			}
		}
		return inc;
	}

	private FinanceBaseInfo findPreFin(List<FinanceBaseInfo> fbis, int year, int quarter) {
		for (FinanceBaseInfo f : fbis) {
			if (f.getYear() == year && f.getQuarter() == quarter) {
				return f;
			}
		}
		return null;
	}

	public YgInfo getyjkb(String code, int currYear, int currQuarter, StringBuffer ykbm) {
		FinYjkb yjkb = getLastFinaceKbByReportDate(code, currYear, currQuarter);
//		boolean hasKb = false;
		// 业绩快报(准确的)
		if (yjkb != null) {
			YgInfo y = new YgInfo();
			StringBuffer sb = new StringBuffer("快报-->");
			sb.append(yjkb.getYear() + "-" + yjkb.getQuarter()).append(" ");
			sb.append("营收同比:").append(yjkb.getYyzsrtbzz()).append("% ");
			sb.append("净利同比:").append(yjkb.getJlrtbzz()).append("% ");
			ykbm.append(sb.toString());

			y.setYear(yjkb.getYear());
			y.setQuarter(yjkb.getQuarter());
			y.setYgjlr(yjkb.getJlr());
			y.setYgjlrtbzz(yjkb.getJlrtbzz());
			return y;
		}
		// 业绩预告(类似天气预报,可能不准)
		FinYjyg yjyg = getLastFinaceYgByReportDate(code, currYear, currQuarter);
		if (yjyg != null) {
			YgInfo y = new YgInfo();
			StringBuffer sb = new StringBuffer("预告-->");
			sb.append(yjyg.getYear() + "-" + yjyg.getQuarter()).append(" ");
			sb.append("净利同比:").append(yjyg.getJlrtbzz()).append("% ").append(yjyg.getType());
			ykbm.append(sb.toString());
			y.setYear(yjyg.getYear());
			y.setQuarter(yjyg.getQuarter());
			y.setYgjlr(yjyg.getJlr());
			y.setYgjlrtbzz(yjyg.getJlrtbzz());
			return y;
		}
		return null;
	}

	public FinYjkb getLastFinaceKbByReportDate(String code) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.matchPhraseQuery("isValid", 1));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).build();

		Page<FinYjkb> page = esFinYjkbDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		return null;
	}

	public FinYjyg getLastFinaceYgByReportDate(String code) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.matchPhraseQuery("isValid", 1));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).build();

		Page<FinYjyg> page = esFinYjygDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		return null;
	}

	public void jobSpiderKuaiYuBao() {
		TasksWorker.getInstance().getService().submit(new MyCallable(RunLogBizTypeEnum.FINACE_FRIST, RunCycleEnum.DAY) {
			public Object mycall() {
				try {
					log.info("同步业绩预报和快报[started]");
					int updateDate = Integer.valueOf(DateUtil.getTodayYYYYMMDD());
					List<FinYjkb> list1 = eastmoneySpider.getFinYjkb();
					List<FinYjyg> list2 = eastmoneySpider.getFinYjyg();
					StringBuffer sb = new StringBuffer();
					if (list1.size() > 0) {
						for (int i = 0; i < list1.size(); i++) {
							FinYjkb fy = list1.get(i);
							if (i < 5) {// 前面5条
								sb.append(stockBasicService.getCodeName(fy.getCode())).append(",");
							}
							fy.setUpdateDate(updateDate);
							fy.setIsValid(1);
						}
						esFinYjkbDao.saveAll(list1);
					}
					if (list2.size() > 0) {
						for (int i = 0; i < list2.size(); i++) {
							FinYjyg fy = list2.get(i);
							if (i <= 5) {
								sb.append(stockBasicService.getCodeName(fy.getCode())).append(",");
							}
							fy.setUpdateDate(updateDate);
							fy.setIsValid(1);
						}
						esFinYjygDao.saveAll(list2);
					}
					log.info("同步业绩预报和快报[end],result=" + sb.toString());
					// WxPushUtil.pushSystem1(
					// "同步业绩预报和快报完成！" + (sb.length() > 0 ? ("今日快报或者预告:" + sb.toString()) :
					// "今日无业绩快报或者预告"));
					monitorPoolService.kybMonitor();
				} catch (Exception e) {
					e.printStackTrace();
					MsgPushServer.pushToSystem("同步业绩预报和快报异常");
					throw e;
				}
				return null;
			}
		});
	}

	public void byWeb() {
		TasksWorker.getInstance().getService()
				.submit(new MyCallable(RunLogBizTypeEnum.FINACE_HISTORY, RunCycleEnum.WEEK) {
					public Object mycall() {
						fetchFinances();
						return null;
					}
				});
	}

	public synchronized void byJob() {
		int date = Integer.valueOf(DateUtil.getTodayYYYYMMDD());
		log.info("模型开始之前运行执行：1.质押，2.股东人数");
		WeekendFinFetchRtl rtl = new WeekendFinFetchRtl();
		new Thread(new Runnable() {
			@Override
			public void run() {
				thsHolderSpider.dofetchHolder(true);// 股东人数
				rtl.setThsHolderOk(true);
			}
		}).start();
		new Thread(new Runnable() {
			@Override
			public void run() {
				fetchFinances();// 财务
				executeHangye(date);
				rtl.setDfFinOk(true);
			}
		}).start();

		while (!rtl.isAllOk()) {
			log.info("没有完成所有事项，继续等待");
			ThreadsUtil.sleep(15, TimeUnit.MINUTES);
		}
		log.info("已完成所有事项");
		// 运行完财务和行业对比后,重新运行
		runModelService.runModel(date, true);
	}

	private List<FinanceBaseInfoHangye> executeHangye(int date) {
		log.info("行业对比开始");
		cache = new HashMap<String, FinanceBaseInfoHangye>();
		List<FinanceBaseInfoHangye> hys = new LinkedList<FinanceBaseInfoHangye>();
		List<StockBaseInfo> list = stockBasicService.getAllOnStatusListWithSort();
		for (StockBaseInfo s : list) {
			String code = s.getCode();
			try {
				List<CodeConcept> cc = conceptService.getCodeConcept(code, 2);// 同花顺行业
				if (cc != null && cc.size() > 0) {
					CodeConcept c = cc.get(0);
					log.info("code={},ConceptId={},ConceptName={}", code, c.getConceptId(), c.getConceptName());
					List<CodeConcept> allcode = conceptService.getCodes(c.getConceptId());
					FinanceBaseInfo fbi = this.getLastFinaceReport(code);
					if (fbi != null) {
						if (!getFromCache(code, fbi.getYear(), fbi.getQuarter())) {// from chace
							executeHangyeExt1(date, fbi.getYear(), fbi.getQuarter(), allcode, hys, c.getConceptId(),
									c.getConceptName());
						}
					}
				}
			} catch (Exception e) {
				MsgPushServer.pushToSystem("行业分析（毛利率，应收占款）计算异常:" + code);
				ErrorLogFileUitl.writeError(e, "行业分析（毛利率，应收占款）计算异常:", "", "");
			}
		}
		if (hys.size() > 0) {
			esFinanceBaseInfoHyDao.saveAll(hys);
		}
		log.info("行业对比结束");
		return hys;
	}

	private boolean getFromCache(String code, int year, int quarter) {
		String key = code + year + "" + quarter;
		if (cache.containsKey(key)) {
			return true;
		}
		return false;
	}

	private Map<String, FinanceBaseInfoHangye> cache = new HashMap<String, FinanceBaseInfoHangye>();

	private void executeHangyeExt1(int updateDate, int year, int quarter, List<CodeConcept> allcode,
			List<FinanceBaseInfoHangye> hys, String hyid, String hyName) {
		double mll = 0.0;
		int mllc = 0;
		double yszk = 0.0;
		int yszkc = 0;
		double xjl = 0.0;
		int xjlc = 0;
		List<FinanceBaseInfo> rl = new LinkedList<FinanceBaseInfo>();
		List<FinanceBaseInfo> yszkl = new LinkedList<FinanceBaseInfo>();
		List<FinanceBaseInfo> xjll = new LinkedList<FinanceBaseInfo>();

		for (CodeConcept c : allcode) {
			log.info("板块：{},code={}", c.getConceptName(), c.getCode());
			FinanceBaseInfo f = this.getFinaceReportByDate(c.getCode(), year, quarter);
			if (f != null) {
				if (f.getMll() != 0) {
					rl.add(f);
					mll += f.getMll();
					mllc++;
				}
				if (f.getAccountrecRatio() != 0) {
					yszkl.add(f);
					yszk += f.getAccountrecRatio();
					yszkc++;
				}
				xjl += f.getMgjyxjl();
				xjlc++;
				xjll.add(f);
			}
		}
		// ====毛利率====start====
		double avgtMll = 0.0;
		if (mllc > 0) {
			avgtMll = CurrencyUitl.roundHalfUp(mll / (double) mllc);
		}
		mllSort(rl);
		for (int i = 0; i < rl.size(); i++) {// 有数据的
			FinanceBaseInfo r = rl.get(i);
			FinanceBaseInfoHangye hy = new FinanceBaseInfoHangye();
			hy.setCode(r.getCode());
			hy.setMll(r.getMll());
			hy.setMllAvg(avgtMll);
			hy.setMllRank((i + 1));
			hy.setYear(year);
			hy.setQuarter(quarter);
			hy.setId(hy.getCode() + year + "" + quarter);
			hy.setHangyeId(hyid);
			hy.setHangyeName(hyName);
			hy.setUpdateDate(updateDate);
			cache.put(hy.getId(), hy);
			hys.add(hy);
		}
		for (CodeConcept c : allcode) {// 无数据的：补全
			String key = c.getCode() + year + "" + quarter;
			if (!cache.containsKey(key)) {
				FinanceBaseInfoHangye hy = new FinanceBaseInfoHangye();
				hy.setCode(c.getCode());
				hy.setMll(0);
				hy.setMllAvg(avgtMll);
				hy.setMllRank(9999);
				hy.setYear(year);
				hy.setQuarter(quarter);
				hy.setId(key);
				hy.setHangyeId(hyid);
				hy.setHangyeName(hyName);
				hy.setUpdateDate(updateDate);
				cache.put(hy.getId(), hy);
				hys.add(hy);
			}
		}
		// ====毛利率====end====

		// ====应收占款比率====start====
		double avgtAr = 0.0;
		if (yszkc > 0) {
			avgtAr = CurrencyUitl.roundHalfUp(yszk / (double) yszkc);
		}
		arSort(yszkl);
		for (CodeConcept c : allcode) {// 初始化
			String key = c.getCode() + year + "" + quarter;
			FinanceBaseInfoHangye hy = cache.get(key);
			hy.setYszk(0);
			hy.setYszkRank(9999);
			hy.setYszkAvg(avgtAr);
		}
		for (int i = 0; i < yszkl.size(); i++) {// 填充有数据的
			FinanceBaseInfo r = yszkl.get(i);
			String key = r.getCode() + year + "" + quarter;
			FinanceBaseInfoHangye hy = cache.get(key);
			hy.setYszk(r.getAccountrecRatio());
			hy.setYszkRank((i + 1));
		}
		// ====应收占款比率====end====

		// ====现金流====start====
		double avgxjl = 0.0;
		if (xjlc > 0) {
			avgxjl = CurrencyUitl.roundHalfUp(xjl / (double) xjlc);
		}
		xjlSort(xjll);
		for (int i = 0; i < xjll.size(); i++) {
			FinanceBaseInfo r = xjll.get(i);
			String key = r.getCode() + year + "" + quarter;
			FinanceBaseInfoHangye hy = cache.get(key);
			hy.setXjl(r.getMgjyxjl());
			hy.setXjlRank((i + 1));
			hy.setXjlAvg(avgxjl);
		}
		// ====现金流====end====
	}

	// 毛利率倒序排序
	private void mllSort(List<FinanceBaseInfo> rl) {
		Collections.sort(rl, new Comparator<FinanceBaseInfo>() {
			@Override
			public int compare(FinanceBaseInfo o1, FinanceBaseInfo o2) {
				if (o1.getMll() == o2.getMll()) {
					return 0;
				}
				return o2.getMll() - o1.getMll() > 0 ? 1 : -1;
			}
		});
	}

	// 应收占款比率倒序排序
	private void arSort(List<FinanceBaseInfo> rl) {
		Collections.sort(rl, new Comparator<FinanceBaseInfo>() {
			@Override
			public int compare(FinanceBaseInfo o1, FinanceBaseInfo o2) {
				if (o1.getAccountrecRatio() == o2.getAccountrecRatio()) {
					return 0;
				}
				return o2.getAccountrecRatio() - o1.getAccountrecRatio() > 0 ? 1 : -1;
			}
		});
	}

	// 现金流(低的排前）
	private void xjlSort(List<FinanceBaseInfo> rl) {
		Collections.sort(rl, new Comparator<FinanceBaseInfo>() {
			@Override
			public int compare(FinanceBaseInfo o1, FinanceBaseInfo o2) {
				if (o1.getMgjyxjl() == o2.getMgjyxjl()) {
					return 0;
				}
				return o2.getMgjyxjl() - o1.getMgjyxjl() > 0 ? -1 : 1;
			}
		});
	}

	public void fetchFinances() {
		int tradeDate = DateUtil.getTodayIntYYYYMMDD();
		int pre1Year = DateUtil.getPreYear(tradeDate);
		log.info("同步财务报告报告[started]");
		List<StockBaseInfo> list = stockBasicService.getAllOnStatusListWithSort();
		int total = list.size();
		log.info("股票总数：" + total);
		List<FinanceBaseInfo> rl = new LinkedList<FinanceBaseInfo>();
		int cnt = 0;
		for (int i = 0; i < list.size(); i++) {
			StockBaseInfo s = list.get(i);
			try {
				if (spiderFinaceHistoryInfo(s.getCode(), rl, s.getDfcwCompnayType(), pre1Year, i)) {
					cnt++;
				}
			} catch (Exception e) {
				e.printStackTrace();
				ErrorLogFileUitl.writeError(e, s.getCode(), s.getDfcwCompnayType(), i);
			}
			if (rl.size() > 1000) {
				esFinanceBaseInfoDao.saveAll(rl);
				rl = new LinkedList<FinanceBaseInfo>();
			}
		}
		if (rl.size() > 0) {
			esFinanceBaseInfoDao.saveAll(rl);
		}
		ThreadsUtil.sleep(10, TimeUnit.MINUTES);
		rl = new LinkedList<FinanceBaseInfo>();
		for (StockBaseInfo s : list) {
			List<FinanceBaseInfo> fbis = getFinacesReportByLteDate(s.getCode(), tradeDate, EsQueryPageUtil.queryPage1);
			if (fbis == null) {
				boolean onlineYear = stockBasicService.onlinePreYearChk(s.getCode(), pre1Year);
				if (onlineYear) {
					ErrorLogFileUitl.writeError(new RuntimeException("无最新财务数据"), s.getCode(), tradeDate + "",
							"Finance Service 错误");
				} else {
					log.info("{},Online 上市不足1年", s.getCode());
				}
				continue;
			}
			// 商誉净利润占比
			FinanceBaseInfo fbi = fbis.get(0);
			if (fbi.getGoodWill() > 0) {
				if (fbi.getGsjlr() > 0) {
					fbi.setGoodWillRatioGsjlr(CurrencyUitl.roundHalfUp(fbi.getGoodWill() / fbi.getGsjlr()));
				} else {
					fbi.setGoodWillRatioGsjlr(9999);
				}
			} else {
				fbi.setGoodWillRatioGsjlr(0);
			}
			rl.add(fbi);
		}
		esFinanceBaseInfoDao.saveAll(rl);
		log.info("同步财务报告报告[end]");
		MsgPushServer.pushToSystem("同步股票财务报告完成！股票总数：[" + total + "],成功股票数[" + cnt + "],失败股票数=" + (total - cnt));
		// monitorPoolService.jobXjlWarning();
	}
}
