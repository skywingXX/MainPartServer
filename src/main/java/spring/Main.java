package spring;

/**
 * 直接运行main方法即可输出翻译结果
 */
public class Main {
	
	public static void main(String[] args) throws Exception {
		String source = "百度翻译引擎示例";
		String result = BaiduTranslateDemo.translateToEn(source);
		if(result == null){
			System.out.println("翻译出错，参考百度错误代码和说明。");
			return;
		}
		System.out.println(source + "：" + result);
	}
}
