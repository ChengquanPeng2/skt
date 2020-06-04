package com.stable.service.model;

import java.util.LinkedList;
import java.util.List;

import com.stable.config.SpringConfig;
import com.stable.service.model.data.LineAvgPrice;
import com.stable.service.model.data.LinePrice;
import com.stable.service.model.data.LineTickData;
import com.stable.service.model.data.LineVol;
import com.stable.utils.FileWriteUitl;
import com.stable.utils.SpringUtil;
import com.stable.utils.WxPushUtil;
import com.stable.vo.ModelContext;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class ImageStrategyListener implements StrategyListener {

	private List<ModelContext> set = new LinkedList<ModelContext>();

	public void condition(ModelContext mv) {
		set.add(mv);
	}

	public void fulshToFile() {
		log.info("List<ModelContext> size:{}", set.size());
		if (set.size() > 0) {
			SpringConfig efc = SpringUtil.getBean(SpringConfig.class);
			String filepath = efc.getModelImageFloder() + "images_" + set.get(0).getDate() + ".html";
			FileWriteUitl fw = new FileWriteUitl(filepath, true);
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < set.size(); i++) {
				ModelContext mc = set.get(i);
				sb.append(String.format("代码:%s,日期:%s,匹配图形:%s", mc.getCode(), mc.getDate(), mc.getImgResult()))
						.append(FileWriteUitl.LINE_HTML).append(FileWriteUitl.LINE_FILE);

				WxPushUtil.pushSystem1("发现图形模型code:" + mc.getCode());
			}
			fw.writeLine(sb.toString());
			fw.close();
		}
	}

	@Override
	public void processingModelResult(ModelContext cxt, LineAvgPrice lineAvgPrice, LinePrice linePrice, LineVol lineVol,
			LineTickData lineTickData) {
	}

}
