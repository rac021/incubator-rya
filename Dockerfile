FROM  tomcat:8.0

ENV CATALINA_HOME /usr/local/tomcat
ENV PATH $CATALINA_HOME/bin:$PATH
WORKDIR $CATALINA_HOME

ADD ./web/web.rya/target/web.rya.war  $CATALINA_HOME/webapps/
	
EXPOSE 8080
CMD ["catalina.sh", "run"]
