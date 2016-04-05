package com.hankcs.nlp.lex;

import com.google.gson.Gson;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.py.Pinyin;
import com.hankcs.hanlp.seg.common.Term;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.international.pennchinese.ChineseTreebankLanguagePack;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.*;

import com.hankcs.nlp.lex.TranslateModel;
import java.net.URL;
import java.net.URLConnection;



/**
 * 提取主谓宾
 *
 * @author hankcs
 */
public class MainPartExtractor
{

    private static final Logger LOG = LoggerFactory.getLogger(MainPartExtractor.class);
    private static LexicalizedParser lp;
    private static GrammaticalStructureFactory gsf;
    static
    {
        //模型
        String models = "models/chineseFactored.ser";
        LOG.info("载入文法模型：" + models);
        lp = LexicalizedParser.loadModel(models);
        //汉语
        TreebankLanguagePack tlp = new ChineseTreebankLanguagePack();
        gsf = tlp.grammaticalStructureFactory();
    }

    /**
     * 获取句子的主谓宾
     *
     * @param sentence 问题
     * @return 问题结构
     */
    public static MainPart getMainPart(String sentence)
    {
        // 去掉不可见字符
        sentence = sentence.replace("\\s+", "");
        // 分词，用空格隔开
        List<Word> wordList = seg(sentence);
        return getMainPart(wordList);
    }

    /**
     * 获取句子的主谓宾
     *
     * @param words    HashWord列表
     * @return 问题结构
     */
    public static MainPart getMainPart(List<Word> words)
    {
        MainPart mainPart = new MainPart();
        if (words == null || words.size() == 0) return mainPart;
        Tree tree = lp.apply(words);
        LOG.info("句法树:{}", tree.pennString());
        // 根据整个句子的语法类型来采用不同的策略提取主干
        switch (tree.firstChild().label().toString())
        {
            case "NP":
                // 名词短语，认为只有主语，将所有短NP拼起来作为主语即可
                mainPart = getNPPhraseMainPart(tree);
                break;
            default:
                GrammaticalStructure gs = gsf.newGrammaticalStructure(tree);
                Collection<TypedDependency> tdls = gs.typedDependenciesCCprocessed(true);
                LOG.info("依存关系：{}", tdls);
                TreeGraphNode rootNode = getRootNode(tdls);
                if (rootNode == null)
                {
                    return getNPPhraseMainPart(tree);
                }
                LOG.info("中心词语：", rootNode);
                mainPart = new MainPart(rootNode);
                for (TypedDependency td : tdls)
                {
                    // 依存关系的出发节点，依存关系，以及结束节点
                    TreeGraphNode gov = td.gov();
                    GrammaticalRelation reln = td.reln();
                    String shortName = reln.getShortName();
                    TreeGraphNode dep = td.dep();
                    if (gov == rootNode)
                    {
                        switch (shortName)
                        {
                            case "nsubjpass":
                            case "dobj":
                            case "attr":
                                mainPart.object = dep;
                                break;
                            case "nsubj":
                            case "top":
                                mainPart.subject = dep;
                                break;
                        }
                    }
                    if (mainPart.object != null && mainPart.subject != null)
                    {
                        break;
                    }
                }
                // 尝试合并主语和谓语中的名词性短语
                combineNN(tdls, mainPart.subject);
                combineNN(tdls, mainPart.object);
                if (!mainPart.isDone()) mainPart.done();
        }

        return mainPart;
    }

    private static MainPart getNPPhraseMainPart(Tree tree)
    {
        MainPart mainPart = new MainPart();
        StringBuilder sbResult = new StringBuilder();
        List<String> phraseList = getPhraseList("NP", tree);
        for (String phrase : phraseList)
        {
            sbResult.append(phrase);
        }
        mainPart.result = sbResult.toString();
        return mainPart;
    }

    /**
     * 从句子中提取最小粒度的短语
     * @param type
     * @param sentence
     * @return
     */
    public static List<String> getPhraseList(String type, String sentence)
    {
        return getPhraseList(type, lp.apply(seg(sentence)));
    }

    private static List<String> getPhraseList(String type, Tree tree)
    {
        List<String> phraseList = new LinkedList<String>();
        for (Tree subtree : tree)
        {
            if(subtree.isPrePreTerminal() && subtree.label().value().equals(type))
            {
                StringBuilder sbResult = new StringBuilder();
                for (Tree leaf : subtree.getLeaves())
                {
                    sbResult.append(leaf.value());
                }
                phraseList.add(sbResult.toString());
            }
        }
        return phraseList;
    }

    /**
     * 合并名词性短语为一个节点
     * @param tdls 依存关系集合
     * @param target 目标节点
     */
    private static void combineNN(Collection<TypedDependency> tdls, TreeGraphNode target)
    {
        if (target == null) return;
        for (TypedDependency td : tdls)
        {
            // 依存关系的出发节点，依存关系，以及结束节点
            TreeGraphNode gov = td.gov();
            GrammaticalRelation reln = td.reln();
            String shortName = reln.getShortName();
            TreeGraphNode dep = td.dep();
            if (gov == target)
            {
                switch (shortName)
                {
                    case "nn":
                        target.setValue(dep.toString("value") + target.value());
                        return;
                }
            }
        }
    }

    private static TreeGraphNode getRootNode(Collection<TypedDependency> tdls)
    {
        for (TypedDependency td : tdls)
        {
            if (td.reln() == GrammaticalRelation.ROOT)
            {
                return td.dep();
            }
        }

        return null;
    }

    /**
     * 分词
     *
     * @param sentence 句子
     * @return 分词结果
     */
    private static List<Word> seg(String sentence)
    {
        //分词
        LOG.info("正在对短句进行分词：" + sentence);
        List<Word> wordList = new LinkedList<>();
        List<Term> terms = HanLP.segment(sentence);
        StringBuffer sbLogInfo = new StringBuffer();
        for (Term term : terms)
        {
            Word word = new Word(term.word);
            wordList.add(word);
            sbLogInfo.append(word);
            sbLogInfo.append(' ');
        }
        LOG.info("分词结果为：" + sbLogInfo);
        return wordList;
    }

    public static MainPart getMainPart(String sentence, String delimiter)
    {
        List<Word> wordList = new LinkedList<>();
        for (String word : sentence.split(delimiter))
        {
            wordList.add(new Word(word));
        }
        return getMainPart(wordList);
    }
    
    
    public static void server(){
    	 ServerSocket ss = null;
    	
    	 InputStreamReader inputStreamReader; 
    	
    	 BufferedReader bufferedReader;
		try {
			//让服务器端程序开始监听来自4242端口的客户端请求
			if (ss==null) {
				 ss = new ServerSocket(4242);
				 System.out.println("服务器启动...");
			}
			
			//服务器无穷的循环等待客户端的请求
			while(true){	
			/*
			 *accept()方法会在等待用户的socket连接时闲置着，当用户链接
			 *上来时，此方法会返回一个socket(在不同的端口上)以便与客户端
			 *通信。Socket与ServerSocket的端口不同，因此ServerSocket可以
			 *空闲出来等待其他客户端
			 */ 
			//这个方法会停下来等待要求到达之后再继续
			Socket s = ss.accept();
			String text = "";
			
			inputStreamReader = new InputStreamReader(s.getInputStream());
			bufferedReader = new BufferedReader(inputStreamReader);
			//new InputStreamReader(inputStreamReader,"UTF-8")
			String request = bufferedReader.readLine();
			// summary转换
			//List<String> sentenceList = HanLP.extractSummary(request, 2);
			//拼音转换
			//String advice = convertToPinyin(request);
			//翻译
			//String advice = translate(request);
			//小黄鸡
			String advice = xiaoHuangJi(request);
			
			System.out.println("接收到了客户端的请求:"+request);
			PrintWriter printWriter = new PrintWriter(s.getOutputStream());
			
			 
			
			printWriter.println(advice);
			printWriter.close();
			text = "";
			
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
	}
    
    	public static String convertToPinyin(String text){
    		String pinyinC ="";
    		List<Pinyin> pinyinList = HanLP.convertToPinyinList(text);
            for (Pinyin pinyin : pinyinList)
            {
            	if(pinyin.getPinyinWithToneMark().equals("none")){
            		pinyinC = pinyinC+" ";
            		continue;
            	}
            		
            	pinyinC = pinyinC+ pinyin.getPinyinWithToneMark();
            }
            return pinyinC;
    	}
    	
    	public static String translate(String source)
    	{
    		String api_url;
    		try {
    			api_url = new StringBuilder("http://fanyi.baidu.com/transapi?from=zh&to=en&query=")
    			.append(URLEncoder.encode(source,"utf-8")).toString();
    			
    			//String json=HttpGet.getHtml(api_url, "utf-8");
    			String json= doGet(api_url, null, "utf-8", true);
    			
    			
    			Gson gson=new Gson();
    			TranslateModel translateMode=gson.fromJson(json, TranslateModel.class);
    			
    			if(translateMode!=null&&translateMode.getData()!=null&&translateMode.getData().size()==1)
    			{
    				return translateMode.getData().get(0).getDst();
    			}
    		} catch (Exception e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		}
    		return null;
    	}

    	public static String doGet(String url, String queryString, String charset, boolean pretty) { 
            StringBuffer response = new StringBuffer(); 
            HttpClient client = new HttpClient(); 
            HttpMethod method = new GetMethod(url); 
            try { 
//                    if (StringUtils.isNotBlank(queryString)) 
//                            //对get请求参数做了http请求默认编码，好像没有任何问题，汉字编码后，就成为%式样的字符串 
//                            method.setQueryString(URIUtil.encodeQuery(queryString)); 
                    client.executeMethod(method); 
                    if (method.getStatusCode() == HttpStatus.SC_OK) { 
                            BufferedReader reader = new BufferedReader(new InputStreamReader(method.getResponseBodyAsStream(), charset)); 
                            String line; 
                            while ((line = reader.readLine()) != null) { 
                                    if (pretty) 
                                            response.append(line).append(System.getProperty("line.separator"));
                                    else 
                                            response.append(line); 
                            } 
                            reader.close(); 
                    } 
            } catch (URIException e) { 
            } catch (IOException e) { 
            } finally { 
                    method.releaseConnection(); 
            } 
            return response.toString(); 
    } 
    	
    	public static String xiaoHuangJi(String source){
    		String api_url;
    		try {
    			api_url = new StringBuilder("http://www.niurenqushi.com/app/simsimi/ajax.aspx?txt=")
    			.append(URLEncoder.encode(source,"utf-8")).toString();
    			
    			//String json=HttpGet.getHtml(api_url, "utf-8");
    			String json= doGet(api_url, null, "utf-8", true);
    			
    			return json;
    		} catch (Exception e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		}
			return null;
    	}
    /**
     * 调用演示
     * @param args
     */
    public static void main(String[] args)
    {
    	server();
    	//System.out.println(translate("你好你是傻逼吗？"));
    	
    	
    }
}