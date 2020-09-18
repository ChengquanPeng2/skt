package com.stable.service.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.stable.config.SpringConfig;
import com.stable.constant.Constant;
import com.stable.enums.ModelType;
import com.stable.service.ConceptService.ConceptInfo;
import com.stable.service.StockBasicService;
import com.stable.service.model.data.LineAvgPrice;
import com.stable.service.model.data.LinePrice;
import com.stable.service.model.data.LinePrice.StrongResult;
import com.stable.service.model.data.LineTickData;
import com.stable.service.model.data.LineVol;
import com.stable.utils.ErrorLogFileUitl;
import com.stable.utils.FileWriteUitl;
import com.stable.utils.SpringUtil;
import com.stable.vo.ModelContext;
import com.stable.vo.bus.DaliyBasicInfo;
import com.stable.vo.bus.StockAvg;
import com.stable.vo.up.strategy.ModelV1;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class V3SortStrategyListener implements StrategyListener {
	private String header = "<table border='1' cellspacing='0' cellpadding='0'><tr>";
	private String endder = "</table><script type='text/javascript' src='/tkhtml/static/addsinaurl.js'></script>";

	private Map<String, String> result = new ConcurrentHashMap<String, String>();

	// 白马,缩量上涨

	List<ModelV1> saveList = Collections.synchronizedList(new LinkedList<ModelV1>());
	private int treadeDate;

	private void setDetail(StringBuffer detailDesc, String desc) {
		detailDesc.append(desc).append(Constant.DOU_HAO);
	}

	public void processingModelResult(ModelContext mc, LineAvgPrice lineAvgPrice, LinePrice linePrice, LineVol lineVol,
			LineTickData lineTickData) {

		ModelV1 mv = new ModelV1();
		mv.setCode(mc.getCode());
		mv.setDate(mc.getDate());
		mv.setModelType(ModelType.SORT_V1.getCode());
		mv.setId(mv.getModelType() + mv.getCode() + mv.getDate());
		StringBuffer detailDesc = new StringBuffer();
		String dropOutMsg = "";

		boolean isOk = false;
		if (StringUtils.isBlank(mc.getBaseDataOk())) {
			int avgScore = 0;
			int strongScore = 0;
			int pgmScore = 0;
			int wayScore = 0;
			int gnScore = 0;

			// 均线
			try {
				if (lineAvgPrice.feedData()) {
					if (lineAvgPrice.isWhiteHorseV2()) {
						setDetail(detailDesc, "白马？");
						mv.setWhiteHorse(1);// 白马？
						DaliyBasicInfo today = mc.getToday();
						if (today.getLow() <= 0 || today.getClose() <= 0) {
							throw new RuntimeException(mc.getCode() + " " + mc.getDate()
									+ " 数据异常,today.getLow()<=0||today.getClose()<=0?");
						}
						//缩量
						//上涨
						//
						
						
					} else {
						isOk = false;
						dropOutMsg = "非白马";
					}
				} else {
					isOk = false;
					dropOutMsg = "未获取到均价-30D";
				}
			} catch (Exception e) {
				isOk = false;
				dropOutMsg = "获取到均价异常";
				e.printStackTrace();
				ErrorLogFileUitl.writeError(e, "均线执行异常", "", "");
			}

			if (isOk) {
				if (!lineTickData.tickDataInfo()) {
					setDetail(detailDesc, "每日指标记录小于5条,checkStrong get size<5");
					dropOutMsg += "每日指标记录小于5条,checkStrong get size<5";
				}

				StrongResult sr = linePrice.strongScore();
				strongScore = sr.getStrongScore();
				if (strongScore > 0) {
					setDetail(detailDesc, sr.getStrongDetail());
				}

				pgmScore = mc.getSortPgm() * 5;// 3.程序单
				wayScore = mc.getSortWay() * 5;// 4.交易方向

				// 概念板块
				List<ConceptInfo> list = mc.getGnDaliy().get(mv.getCode());
				if (list != null) {
					for (int i = 0; i < list.size(); i++) {
						ConceptInfo x = list.get(i);
						gnScore += x.getRanking();
						setDetail(detailDesc, x.toString());
					}
				}
				result.put(mv.getCode(), detailDesc.toString());
			} else {
				log.info("code={},dropOutMsg={}", mc.getCode(), dropOutMsg);
			}
			mv.setAvgScore(avgScore);
			mv.setSortStrong(strongScore);
			mv.setSortPgm(pgmScore);
			mv.setSortWay(wayScore);
			mv.setGnScore(gnScore);
			mv.setPriceIndex(mc.getPriceIndex());
			mv.setScore(avgScore + strongScore + pgmScore + wayScore + gnScore);
			if (isOk) {
				saveList.add(mv);
			}
		} else {
			log.info("code={},dropOutMsg={}", mc.getCode(), mc.getBaseDataOk());
		}
	}

	// **评分

	public V3SortStrategyListener(int date) {
		this.treadeDate = date;
		String[] s = { "序号", "代码", "简称", "日期", "综合评分", "均线价格", "短期强势", "主力行为", "市场行为", "价格指数", "评分详情" };
		for (int i = 0; i < s.length; i++) {
			header += this.getHTMLTH(s[i]);
		}
		header += "</tr>" + FileWriteUitl.LINE_FILE;
	}

	public void fulshToFile() {
		StringBuffer sb2 = new StringBuffer(header);
		SpringConfig efc = SpringUtil.getBean(SpringConfig.class);
		log.info("saveList size:{}", saveList.size());
		if (saveList.size() > 0) {
			StockBasicService sbs = SpringUtil.getBean(StockBasicService.class);
			sort(saveList);
			int index = 1;
			for (ModelV1 mv : saveList) {
				String code = mv.getCode();
				sb2.append("<tr>").append(getHTML(index)).append(getHTML_SN(code))
						.append(getHTML(sbs.getCodeName(code))).append(getHTML(mv.getDate()))
						.append(getHTML(mv.getScore())).append(getHTML(mv.getAvgScore()))
						.append(getHTML(mv.getSortStrong())).append(getHTML(mv.getSortPgm()))
						.append(getHTML(mv.getSortWay())).append(getHTML(mv.getPriceIndex()))
						.append(getHTML(
								result.get(code) + ",weekOk?" + mv.getWeekOk() + ",振幅超30%?" + mv.getIsRange30p()))
						.append("</tr>").append(FileWriteUitl.LINE_FILE);
				index++;
			}
		} else {
			sb2.append("<tr><td>无记录</td></tr>").append(FileWriteUitl.LINE_FILE);
		}
		sb2.append(endder);
		String filepath2 = efc.getModelV1SortFloderDesc() + "sort_v2_" + treadeDate + ".html";
		FileWriteUitl fw2 = new FileWriteUitl(filepath2, true);
		fw2.writeLine(sb2.toString());
		fw2.close();
	}

	private String getHTML(Object text) {
		return "<td>" + text + "</td>";
	}

	private String getHTML_SN(Object text) {
		return "<td class='sn'>" + text + "</td>";
	}

	private String getHTMLTH(Object text) {
		return "<th>" + text + "</th>";
	}

	private void sort(List<ModelV1> set) {
		Collections.sort(set, new Comparator<ModelV1>() {
			@Override
			public int compare(ModelV1 o1, ModelV1 o2) {
				return o2.getScore() - o1.getScore();
			}
		});
	}

	@Override
	public List<ModelV1> getResultList() {
		return saveList;
	}
}