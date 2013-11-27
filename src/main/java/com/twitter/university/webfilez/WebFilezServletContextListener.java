package com.twitter.university.webfilez;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebFilezServletContextListener implements ServletContextListener {

    private static Logger logger = LoggerFactory
            .getLogger(WebFilezServletContextListener.class);

    @Override
    public void contextInitialized(ServletContextEvent e) {
        Config config = Config.buildAndRegisterConfig(e.getServletContext());
        logger.info("Initialized with " + config);
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        logger.info("Shutting down");
    }
}
