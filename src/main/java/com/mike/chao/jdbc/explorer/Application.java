package com.mike.chao.jdbc.explorer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, normalizeConfigFileArgs(args));
	}

	static String[] normalizeConfigFileArgs(String[] args) {
		if (args == null || args.length == 0) {
			return args;
		}

		var normalizedArgs = new java.util.ArrayList<String>();
		for (int i = 0; i < args.length; i++) {
			if (isConfigFileFlag(args[i]) && i + 1 < args.length && !args[i + 1].startsWith("--")) {
				normalizedArgs.add(args[i] + "=" + args[++i]);
			} else {
				normalizedArgs.add(args[i]);
			}
		}
		return normalizedArgs.toArray(String[]::new);
	}

	private static boolean isConfigFileFlag(String arg) {
		return "--config-file".equals(arg) || "--db.config-file".equals(arg);
	}

}
