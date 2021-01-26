package com.stable.service;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.SearchQuery;
import org.springframework.stereotype.Service;

import com.stable.constant.EsQueryPageUtil;
import com.stable.enums.ZfStatus;
import com.stable.es.dao.base.EsHolderNumDao;
import com.stable.es.dao.base.EsHolderPercentDao;
import com.stable.es.dao.base.FenHongDao;
import com.stable.es.dao.base.JiejinDao;
import com.stable.es.dao.base.ZengFaDao;
import com.stable.es.dao.base.ZengFaDetailDao;
import com.stable.es.dao.base.ZengFaExtDao;
import com.stable.es.dao.base.ZengFaSummaryDao;
import com.stable.spider.ths.ThsAnnSpider;
import com.stable.utils.CurrencyUitl;
import com.stable.utils.DateUtil;
import com.stable.utils.WxPushUtil;
import com.stable.vo.bus.FenHong;
import com.stable.vo.bus.HolderNum;
import com.stable.vo.bus.HolderPercent;
import com.stable.vo.bus.Jiejin;
import com.stable.vo.bus.ZengFa;
import com.stable.vo.bus.ZengFaDetail;
import com.stable.vo.bus.ZengFaExt;
import com.stable.vo.bus.ZengFaSummary;
import com.stable.vo.http.resp.ZengFaResp;
import com.stable.vo.spi.req.EsQueryPageReq;

/**
 * 筹码
 */
@Service
//@Log4j2
public class ChipsService {
	@Autowired
	private EsHolderNumDao esHolderNumDao;
	@Autowired
	private EsHolderPercentDao esHolderPercentDao;
	@Autowired
	private FenHongDao fenHongDao;
	@Autowired
	private ZengFaDao zengFaDao;
	@Autowired
	private ZengFaDetailDao zengFaDetailDao;
	@Autowired
	private ZengFaSummaryDao zengFaSummaryDao;
	@Autowired
	private JiejinDao jiejinDao;
	@Autowired
	private ZengFaExtDao zengFaExtDao;
	@Autowired
	private StockBasicService stockBasicService;

	/**
	 * 最后的增发记录
	 */
	public FenHong getFenHong(String code) {
		int pageNum = EsQueryPageUtil.queryPage1.getPageNum();
		int size = EsQueryPageUtil.queryPage1.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withPageable(pageable).build();
		Page<FenHong> page = fenHongDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		return new FenHong();
	}

	/**
	 * 增发-概况
	 */
	public ZengFaSummary getZengFaSummary(String code) {
		int pageNum = EsQueryPageUtil.queryPage1.getPageNum();
		int size = EsQueryPageUtil.queryPage1.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withPageable(pageable).build();
		Page<ZengFaSummary> page = zengFaSummaryDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		return new ZengFaSummary();
	}

	/**
	 * 最后的增发记录
	 */
	public ZengFa getLastZengFa(String code) {
		int pageNum = EsQueryPageUtil.queryPage1.getPageNum();
		int size = EsQueryPageUtil.queryPage1.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		FieldSortBuilder sort = SortBuilders.fieldSort("startDate").unmappedType("integer").order(SortOrder.DESC);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();
		Page<ZengFa> page = zengFaDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		return new ZengFa();
	}

	/**
	 * 最后的增发详情记录
	 */
	public ZengFaDetail getLastZengFaDetail(String code) {
		int pageNum = EsQueryPageUtil.queryPage1.getPageNum();
		int size = EsQueryPageUtil.queryPage1.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();
		Page<ZengFaDetail> page = zengFaDetailDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		return new ZengFaDetail();
	}

	/**
	 * 前后1年的解禁记录（2年）
	 */
	public List<Jiejin> getBf2yearJiejin(String code, int start, int end) {
		int pageNum = EsQueryPageUtil.queryPage9999.getPageNum();
		int size = EsQueryPageUtil.queryPage9999.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("date").from(start).to(end));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();
		Page<Jiejin> page = jiejinDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		return null;
	}

	public List<Jiejin> getBf2yearJiejin(String code) {
		Date now = new Date();
		int start = DateUtil.formatYYYYMMDDReturnInt(DateUtil.addDate(now, -370));
		int end = DateUtil.formatYYYYMMDDReturnInt(DateUtil.addDate(now, 370));
		return getBf2yearJiejin(code, start, end);
	}

	/**
	 * -解禁记录
	 */
	public List<Jiejin> getAddJiejinList(String code, EsQueryPageReq querypage) {
		int pageNum = querypage.getPageNum();
		int size = querypage.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		if (StringUtils.isNotBlank(code)) {
			bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		}
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();
		Page<Jiejin> page = jiejinDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		return null;
	}

	/**
	 * -增发记录
	 */
	public List<ZengFaResp> getZengFaListForWeb(String code, String status, EsQueryPageReq querypage) {
		List<ZengFa> list = getZengFaList(code, status, querypage);
		if (list != null) {
			List<ZengFaResp> l = new LinkedList<ZengFaResp>();
			for (ZengFa zf : list) {
				ZengFaResp r = new ZengFaResp();
				BeanUtils.copyProperties(zf, r);
				r.setCodeName(stockBasicService.getCodeName(zf.getCode()));
				l.add(r);
			}
			return l;
		}
		return null;
	}

	/**
	 * -增发记录
	 */
	public List<ZengFa> getZengFaList(String code, String status, EsQueryPageReq querypage) {
		return getZengFaList(code, status, 0, querypage);
	}

	public void jobZengFaExt(boolean isJob) {
		int endDate = 0; // 全部
		if (isJob) {
			endDate = DateUtil.formatYYYYMMDDReturnInt(DateUtil.addDate(new Date(), -90));
		}
		StringBuffer sb = new StringBuffer();
		List<ZengFa> l = getZengFaList("", ZfStatus.DONE.getCode() + "", endDate, EsQueryPageUtil.queryPage9999);
		for (ZengFa zf : l) {
			ZengFaExt zfe = getZengFaExtById(zf.getId());
			if (zfe == null) {
				zfe = new ZengFaExt();
				zfe.setId(zf.getId());
				zfe.setCode(zf.getCode());
				zfe.setDate(zf.getEndDate());
				String s = ThsAnnSpider.dofetch(zf.getCode(), zf.getStartDate());
				if (StringUtils.isNotBlank(s)) {
					zfe.setBuy(1);
					zfe.setTitle(s);
					sb.append(zfe.getCode()).append(",");
				}
				zengFaExtDao.save(zfe);
			}
		}
		if (sb.length() > 0) {
			WxPushUtil.pushSystem1("增发完成且是购买资产：" + sb.toString());
		}
	}

	public ZengFaExt getZengFaExtById(String id) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("id", id));
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).build();
		Page<ZengFaExt> page = zengFaExtDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0);
		}
		return null;
	}

	public List<ZengFa> getZengFaList(String code, String status, int endDate, EsQueryPageReq querypage) {
		int pageNum = querypage.getPageNum();
		int size = querypage.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		if (StringUtils.isNotBlank(code)) {
			bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		}
		if (endDate > 0) {
			bqb.must(QueryBuilders.rangeQuery("endDate").from(endDate));
		}
		FieldSortBuilder sort = SortBuilders.fieldSort("startDate").unmappedType("integer").order(SortOrder.DESC);
		if (StringUtils.isNotBlank(status)) {
			bqb.must(QueryBuilders.matchPhraseQuery("status", Integer.valueOf(status)));
			sort = SortBuilders.fieldSort("endDate").unmappedType("integer").order(SortOrder.DESC);
		}
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();
		Page<ZengFa> page = zengFaDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		return null;
	}

	/**
	 * 最新的前3大股东占比
	 */
	public double getLastHolderPercent(String code) {
		int pageNum = EsQueryPageUtil.queryPage1.getPageNum();
		int size = EsQueryPageUtil.queryPage1.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();
		Page<HolderPercent> page = esHolderPercentDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent().get(0).getTopThree();
		}
		return 0.0;
	}

	/**
	 * 最近44条记录
	 */
	public List<HolderNum> getHolderNumList45(String code) {
		int pageNum = EsQueryPageUtil.queryPage45.getPageNum();
		int size = EsQueryPageUtil.queryPage45.getPageSize();
		Pageable pageable = PageRequest.of(pageNum, size);
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		FieldSortBuilder sort = SortBuilders.fieldSort("date").unmappedType("integer").order(SortOrder.DESC);
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).withSort(sort).withPageable(pageable).build();
		Page<HolderNum> page = esHolderNumDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return page.getContent();
		}
		return null;
	}

	/**
	 * 股东人数增长/减少分析（幅度+次数）
	 */
	public double holderNumAnalyse(String code) {
		try {
			List<HolderNum> list = getHolderNumList45(code);
			if (list != null && list.size() > 1) {
				int c2 = 0;
				int lowNum = 0;
				// 增加
				for (int i = 0; i < list.size() - 1; i++) {
					if (list.get(i).getNum() >= list.get(i + 1).getNum()) {
						c2++;
						lowNum = list.get(i + 1).getNum();
					} else {
						break;
					}
				}
				if (c2 > 0) {
					int start = list.get(0).getNum();
					// lowNum/start
					int reducePresent = Double
							.valueOf(CurrencyUitl.cutProfit(Double.valueOf(lowNum), Double.valueOf(start))).intValue();
					if (c2 < 10) {
						return Double.valueOf(reducePresent + ".0" + c2);
					} else {
						return Double.valueOf(reducePresent + "." + c2);
					}
				}
				// 减少
				int c1 = 0;
				int highNum = 0;
				for (int i = 0; i < list.size() - 1; i++) {
					if (list.get(i).getNum() <= list.get(i + 1).getNum()) {
						c1++;
						highNum = list.get(i + 1).getNum();
					} else {
						break;
					}
				}
				if (c1 > 0) {
					int start = list.get(0).getNum();
					// start/lowNum
					int reducePresent = Double
							.valueOf(CurrencyUitl.cutProfit(Double.valueOf(highNum), Double.valueOf(start))).intValue();
					double t = 0.0;
					if (c1 < 10) {
						t = Double.valueOf(reducePresent + ".0" + c1);
					} else {
						t = Double.valueOf(reducePresent + "." + c1);
					}
					// 变化太小导致reducePresent=0，没有-负数符号
					if (reducePresent == 0) {
						return (0 - t);
					} else {
						return t;
					}
				}
			}
			return 0.0;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
}
