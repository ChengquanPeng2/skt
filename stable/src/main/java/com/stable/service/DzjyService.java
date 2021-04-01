package com.stable.service;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.SearchQuery;
import org.springframework.stereotype.Service;

import com.stable.es.dao.base.DzjyDao;
import com.stable.es.dao.base.DzjyYiTimeDao;
import com.stable.vo.bus.Dzjy;
import com.stable.vo.bus.DzjyYiTime;

/**
 * 
 * 大宗
 */
@Service
//@Log4j2
public class DzjyService {
	@Autowired
	private DzjyDao dzjyDao;
	@Autowired
	private DzjyYiTimeDao dzjyYiTimeDao;

	/**
	 * 一段时间内的交易额
	 * 
	 * @param startDate 开始到现在
	 */
	public double halfOver1Yi(String code, int startDate) {
		double t = 0.0;
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("date").gte(startDate));
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).build();

		Page<Dzjy> page = dzjyDao.search(sq);
		if (page != null && !page.isEmpty()) {
			for (Dzjy d : page.getContent()) {
				t += d.getTval();
			}
		}
		return t;
	}

	public boolean dzjyF(String code, int date) {
		BoolQueryBuilder bqb = QueryBuilders.boolQuery();
		bqb.must(QueryBuilders.matchPhraseQuery("code", code));
		bqb.must(QueryBuilders.rangeQuery("date").gte(date));
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
		SearchQuery sq = queryBuilder.withQuery(bqb).build();

		Page<DzjyYiTime> page = dzjyYiTimeDao.search(sq);
		if (page != null && !page.isEmpty()) {
			return true;
		}
		return false;

	}
}
