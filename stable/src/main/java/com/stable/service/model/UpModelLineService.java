package com.stable.service.model;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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

import com.alibaba.fastjson.JSONArray;
import com.stable.constant.EsQueryPageUtil;
import com.stable.constant.RedisConstant;
import com.stable.es.dao.base.EsModelV1Dao;
import com.stable.es.dao.base.MonitoringDao;
import com.stable.service.ConceptService;
import com.stable.service.ConceptService.ConceptInfo;
import com.stable.service.DaliyBasicHistroyService;
import com.stable.service.DaliyTradeHistroyService;
import com.stable.service.PriceLifeService;
import com.stable.service.StockBasicService;
import com.stable.service.TickDataService;
import com.stable.service.TradeCalService;
import com.stable.service.model.data.AvgService;
import com.stable.service.model.data.LineAvgPrice;
import com.stable.service.model.data.LinePrice;
import com.stable.service.model.data.LineTickData;
import com.stable.service.model.data.LineVol;
import com.stable.service.model.data.StrongService;
import com.stable.service.trace.SortV4Service;
import com.stable.spider.tushare.TushareSpider;
import com.stable.utils.DateUtil;
import com.stable.utils.ErrorLogFileUitl;
import com.stable.utils.TasksWorker2ndRunnable;
import com.stable.utils.RedisUtil;
import com.stable.utils.TasksWorker2nd;
import com.stable.utils.WxPushUtil;
import com.stable.vo.ModelContext;
import com.stable.vo.bus.DaliyBasicInfo;
import com.stable.vo.bus.Monitoring;
import com.stable.vo.bus.PriceLife;
import com.stable.vo.bus.StockBaseInfo;
import com.stable.vo.spi.req.EsQueryPageReq;
import com.stable.vo.up.strategy.ModelV1;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class UpModelLineService {

	@Autowired
	private StrongService strongService;
	@Autowired
	private TickDataService tickDataService;
	@Autowired
	private StockBasicService stockBasicService;
	@Autowired
	private PriceLifeService priceLifeService;
	@Autowired
	private AvgService avgService;
	@Autowired
	private DaliyBasicHistroyService daliyBasicHistroyService;
	@Autowired
	private EsModelV1Dao esModelV1Dao;
	@Autowired
	private RedisUtil redisUtil;
	@Autowired
	private TradeCalService tradeCalService;
	@Autowired
	private TushareSpider tushareSpider;
	@Autowired
	private ConceptService conceptService;
	@Autowired
	private DaliyTradeHistroyService daliyTradeHistroyService;
	@Autowired
	private MonitoringDao monitoringDao;
	@Autowired
	private CodeModelService codeModelService;
	@Autowired
	private SortV4Service sortV4Service;

	private final EsQueryPageReq queryPage250 = EsQueryPageUtil.queryPage250;
	private final EsQueryPageReq deleteQueryPage9999 = EsQueryPageUtil.queryPage9999;

	public synchronized void runJob(boolean isJob, int today) {
		try {
			if (isJob) {
				int redisDate = 0;
				String strDate = redisUtil.get(RedisConstant.RDS_MODEL_V1_DATE);
				if (StringUtils.isBlank(strDate)) {// 无缓存，从当天开始
					redisDate = Integer.valueOf(DateUtil.getTodayYYYYMMDD());
				} else {// 缓存的日期是已经执行过，需要+1天
					Date d = DateUtil.addDate(strDate, 1);
					redisDate = Integer.valueOf(DateUtil.formatYYYYMMDD(d));
				}
				while (true) {
					if (tradeCalService.isOpen(redisDate)) {
						log.info("processing date={}", redisDate);
						run(isJob, redisDate);
					} else {
						log.info("{}非交易日", redisDate);
					}
					// 缓存已经处理的日期
					redisUtil.set(RedisConstant.RDS_MODEL_V1_DATE, redisDate);
					// 新增一天
					Date d1 = DateUtil.addDate(redisDate + "", 1);
					redisDate = Integer.valueOf(DateUtil.formatYYYYMMDD(d1));
					if (redisDate > today) {
						log.info("today:{},date:{} 循环结束", today, redisDate);
						break;
					}
				}
			} else {// 手动某一天
				if (!tradeCalService.isOpen(today)) {
					log.info("{}非交易日", today);
					return;
				}
				log.info("processing date={}", today);
				List<ModelV1> deleteall = getListByCode(null, today + "", null, null, null, deleteQueryPage9999, null);
				if (deleteall != null) {
					log.info("删除当天{}记录条数{}", today, deleteall.size());
					esModelV1Dao.deleteAll(deleteall);
					Thread.sleep(3 * 1000);
				}
				log.info("模型date={}开始", today);
				run(isJob, today);
			}
		} catch (Exception e) {
			e.printStackTrace();
			ErrorLogFileUitl.writeError(e, "模型运行异常", "", "");
			WxPushUtil.pushSystem1("模型运行异常..");
		}
		if (isJob) {
			sortV4Service.sortv4(today + "", today + "");
		}
	}

	private void run(boolean isJob, int treadeDate) {
		String startTime = DateUtil.getTodayYYYYMMDDHHMMSS();
		JSONArray array = tushareSpider.getStockDaliyBasic(null, treadeDate + "", null, null).getJSONArray("items");
		if (array == null || array.size() <= 0) {
			log.warn("未获取到日交易daily_basic（每日指标）记录,tushare,日期={}", treadeDate);
			throw new RuntimeException("交易日但未获取到数据");
		}
		List<StrategyListener> models = new LinkedList<StrategyListener>();
		models.add(new SortV4PREStrategyListener(treadeDate, codeModelService, sortV4Service));
		// models.add(new V1SortStrategyListener(treadeDate, codeModelService));
		// models.add(new V2SortStrategyListener(treadeDate));
		// models.add(new V2PRESortStrategyListener(treadeDate));
		if (models.size() <= 0) {
			return;
		}
		try {
			Map<String, List<ConceptInfo>> gn = conceptService.getDailyMap(treadeDate);
			int size = array.size();
			log.info("{}获取到每日指标记录条数={}", treadeDate, size);
			Map<String, DaliyBasicInfo> todayDailyBasicMap = new HashMap<String, DaliyBasicInfo>();
			for (int i = 0; i < size; i++) {
				DaliyBasicInfo d = new DaliyBasicInfo(array.getJSONArray(i));
				todayDailyBasicMap.put(d.getCode(), d);
			}
			List<StockBaseInfo> allOnlieList = stockBasicService.getAllOnStatusList();
			log.info("{}获取上市股票数={}", treadeDate, allOnlieList.size());
			CountDownLatch cunt = new CountDownLatch(allOnlieList.size());
			for (int i = 0; i < allOnlieList.size(); i++) {
				StockBaseInfo sbi = allOnlieList.get(i);
				if (!stockBasicService.online1YearChk(sbi.getCode(), treadeDate)) {
					log.info("{} 上市不足1年", sbi.getCode());
					cunt.countDown();
					continue;
				}
				DaliyBasicInfo d = todayDailyBasicMap.get(sbi.getCode());
				if (d == null) {
					log.info("{} 在{} 没进行交易,用历史数据再跑1次", sbi.getCode(), treadeDate);
					d = daliyBasicHistroyService.queryLastest(sbi.getCode());
					if (d == null) {
						log.info("{} 在{} 没进行交易,用历史数据再跑1次", sbi.getCode(), treadeDate);
						ErrorLogFileUitl.writeError(new RuntimeException("未找到daliyBasicHistroy记录"), sbi.getCode(), "",
								"");
						cunt.countDown();
						continue;
					}
				}

				ModelContext cxt = new ModelContext();
				cxt.setCode(d.getCode());
				cxt.setDate(d.getTrade_date());
				cxt.setToday(d);// 未包含全部信息-来自Tushare
				cxt.setGnDaliy(gn);

				TasksWorker2nd.add(new TasksWorker2ndRunnable() {
					@Override
					public void running() {
						try {
							runModels(cxt, models, treadeDate);
						} catch (Exception e) {
							e.printStackTrace();
							ErrorLogFileUitl.writeError(e, "", "", "");
						} finally {
							cunt.countDown();
						}
					}
				});
			}
			if (!cunt.await(12, TimeUnit.HOURS)) {// 等待执行完成
				log.info("模型执行完成超时异常==>" + treadeDate);
			}
			int v1cnt = 0;

			List<Monitoring> ml = new LinkedList<Monitoring>();
			for (int i = 0; i < models.size(); i++) {
				StrategyListener sort = models.get(i);
				sort.fulshToFile();
				if (sort.getResultList().size() > 0) {
					v1cnt = sort.getResultList().size();
					esModelV1Dao.saveAll(sort.getResultList());
				}
				if (isJob) {
					List<Monitoring> t = sort.getMonitoringList();
					if (t != null && t.size() > 0) {
						ml.addAll(t);
					}
				}
			}
			if (ml.size() > 0) {
				monitoringDao.saveAll(ml);
			}
			log.info("MV1模型执行完成");
			WxPushUtil.pushSystem1("Seq4=> " + treadeDate + " -> MV模型执行完成！ 开始时间:" + startTime + " 结束时间："
					+ DateUtil.getTodayYYYYMMDDHHMMSS() + ", V1满足条件获取数量:" + v1cnt);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new RuntimeException("数据处理异常", e);
		}
	}

	private List<DaliyBasicInfo> getBasicList(ModelContext cxt) {
		return daliyBasicHistroyService.queryListByCodeForModel(cxt.getCode(), cxt.getDate(), queryPage250)
				.getContent();
	}

	private void runModels(ModelContext cxt, List<StrategyListener> models, int treadeDate) {
		// log.info("model version processing for code:{}", code);
		List<DaliyBasicInfo> dailyList = getBasicList(cxt);
		if (dailyList == null || dailyList.size() < 5) {
			ErrorLogFileUitl.writeError(new RuntimeException("每日指标记录小于5条,checkStrong get size<5"), cxt.getCode(), "",
					"");
			return;
		}
		LineAvgPrice lineAvgPrice = null;
		LinePrice linePrice = null;
		LineVol lineVol = null;
		LineTickData lineTickData = null;

		cxt.setToday(dailyList.get(0));// 包含全部信息-来自ES
		// 均价
		lineAvgPrice = new LineAvgPrice(avgService);
		// 1强势:次数和差值:3/5/10/20/120/250天
		linePrice = new LinePrice(strongService, cxt, dailyList, lineAvgPrice.todayAv, daliyTradeHistroyService);
		lineVol = new LineVol(cxt.getCode(), cxt.getDate(), daliyTradeHistroyService);
		// 2交易方向:次数和差值:3/5/10/20/120/250天
		// 3程序单:次数:3/5/10/20/120/250天
		lineTickData = new LineTickData(cxt, dailyList, tickDataService);
		lineTickData.tickDataInfo();// TickData数据
		cxt.setPriceIndex(this.priceIndex(cxt.getToday()));
		for (StrategyListener m : models) {
			m.processingModelResult(cxt, lineAvgPrice, linePrice, lineVol, lineTickData);
		}
	}

	// 收盘价介于最高价和最低价的index
	private int priceIndex(DaliyBasicInfo b) {
		PriceLife pl = priceLifeService.getPriceLife(b.getCode());
		if (pl == null || b.getClose() <= pl.getLowest()) {
			return 0;
		} else if (b.getClose() >= pl.getHighest()) {
			return 100;
		} else {
			double base = pl.getHighest() - pl.getLowest();
			double diff = b.getClose() - pl.getLowest();
			int present = Double.valueOf(diff / base * 100).intValue();
			return present;
		}
	}

	public List<Monitoring> getListByCodeForList(String code) {
		Pageable pageable = PageRequest.of(queryPage250.getPageNum(), queryPage250.getPageSize());
		List<Monitoring> r = new LinkedList<Monitoring>();
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("lastMoniDate").gt(0));
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withPageable(pageable).build();
		Page<Monitoring> page = monitoringDao.search(sq);
		if (page != null && !page.isEmpty()) {
			r.addAll(page.getContent());
		}
		return r;
	}

	public Set<Monitoring> getListByCodeForSet(EsQueryPageReq querypage) {
		Pageable pageable = PageRequest.of(querypage.getPageNum(), querypage.getPageSize());
		Set<Monitoring> r = new HashSet<Monitoring>();
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.rangeQuery("lastMoniDate").gt(0));
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withPageable(pageable).build();
		Page<Monitoring> page = monitoringDao.search(sq);
		if (page != null && !page.isEmpty()) {
			r.addAll(page.getContent());
		}
		return r;
	}

	public List<ModelV1> getListByCode(String code, String date, String whiteHorse, String score, String imageIndex,
			EsQueryPageReq querypage, Integer modelType) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		if (StringUtils.isNotBlank(code)) {
			bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		}
		if (StringUtils.isNotBlank(date)) {
			bqb.must(QueryBuilders.matchPhraseQuery("date", date));
		}
		if (StringUtils.isNotBlank(score)) {
			bqb.must(QueryBuilders.rangeQuery("score").gte(score));
		}
		if (StringUtils.isNotBlank(imageIndex)) {
			bqb.must(QueryBuilders.matchPhraseQuery("imageIndex", 1));
		}
		if (modelType != null) {
			bqb.must(QueryBuilders.matchPhraseQuery("modelType", modelType));
		}
		if (StringUtils.isNotBlank(whiteHorse)) {
			bqb.must(QueryBuilders.matchPhraseQuery("whiteHorse", 1));
		}
		FieldSortBuilder sort = SortBuilders.fieldSort("score").unmappedType("integer").order(SortOrder.DESC);

		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		Pageable pageable = PageRequest.of(querypage.getPageNum(), querypage.getPageSize());
		SearchQuery sq = queryBuilder.withQuery(bqb).withPageable(pageable).withSort(sort).build();

		Page<ModelV1> page = esModelV1Dao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		log.info("no records BuyTrace");
		return null;
	}
}
