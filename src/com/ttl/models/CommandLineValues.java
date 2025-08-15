package com.ttl.models;

public class CommandLineValues {
	public String basepath = null;
	public String outputfilepath = null;
	
	public CommandLineValues() {}
	public CommandLineValues(String basepath, String outputfilepath) {
		this.basepath = basepath;
		this.outputfilepath = outputfilepath;
	}
}
