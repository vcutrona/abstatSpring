package com.summarization.web;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;

import org.apache.commons.lang3.StringUtils;

public class QueryString {

	private ArrayList<String> parameters;

	public QueryString() {
		this.parameters = new ArrayList<String>();
	}
	
	public QueryString addParameter(String queryParameter, String solrParameter, String value) throws UnsupportedEncodingException {
		parameters.add(queryParameter + "=" + solrParameter + ":" + encode(value));
		return this;
	}
	
	public QueryString addParameter( String solrParameter, String value) throws UnsupportedEncodingException {
		parameters.add(solrParameter + "=" + encode(value));
		return this;
	}


	private String encode(String value) throws UnsupportedEncodingException {
		return URLEncoder.encode(value, "UTF-8");
	}

	public String build() {
		return "?" + StringUtils.join(parameters, "&");
	}

}
