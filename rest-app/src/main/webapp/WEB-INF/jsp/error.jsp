#-------------------------------------------------------------------------------
# error.jsp
# rest-app
# Created by Gooru on 2014
# Copyright (c) 2014 Gooru. All rights reserved.
# http://www.goorulearning.org/
# Permission is hereby granted, free of charge, to any person obtaining
# a copy of this software and associated documentation files (the
# "Software"), to deal in the Software without restriction, including
# without limitation the rights to use, copy, modify, merge, publish,
# distribute, sublicense, and/or sell copies of the Software, and to
# permit persons to whom the Software is furnished to do so, subject to
# the following conditions:
# The above copyright notice and this permission notice shall be
# included in all copies or substantial portions of the Software.
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
# NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
# LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
# OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
# WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
#-------------------------------------------------------------------------------
<%@ page language="java" isErrorPage="true" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>

<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">

<html>
<head>
    <title>Error Page</title>
    <link rel="stylesheet" type="text/css" media="all" href="<c:url value='/styles/${appConfig["csstheme"]}/theme.css'/>" />
    
    <style>
	#main {
	font-family:tahoma;
	font-weight:bold;
	font-size:13px;
	background-color:rgb(247,247,247); 
	border: 1px solid rgb(189,190,189);
	width:90%;
	margin: auto;
	margin: 5em auto auto;
	color:rgb(66,73,74);
	padding-bottom: 5em;
	height:550px; 
	}
	#erorText {
	font-size:1em;
	font-weight:bold;
	padding:0.5em;
	background-color:rgb(231,231,231);
	color:rgb(82,73,82);
	}
	
	
	a:link {text-decoration:underline;color:rgb(0,158,206);font-weight:normal;}
	a:visited {text-decoration:underline;color:rgb(0,158,206);font-weight:normal;}
	a:hover {text-decoration:none;color:rgb(0,158,206);font-weight:normal;}
	a:active {text-decoration:none;color:rgb(0,158,206);font-weight:normal;}
</style>

</head>

<body id="error">


	<div>
		<div id="main">
			<div id="erorText">Error</div>
			<div id="errorMainHeader" style="height:50px;"><img style="float:left;" src="images/error.png" ></img><div id="errorMainHeaderText" style="float:left;padding:13px 0px 0px 6px;" >An error has occured in Gooru. Please contact Gooru administrator for further support.</div></div>
			 <div id="errorSubHeader" style="margin:40px 0px 6px 10em;text-align:left;width:850px;" ><img src="images/movedown_normal.png" style="float:left;margin:5px;"></img><div id="viewIssueDetailsText">View Issue Details</div></div>
			<div style="background-color:rgb(255,255,255); border: 1px solid rgb(228,228,228); margin:0 10em 6em 10em; padding:10px 5px 125px 15px;height:50%;width:70%;overflow:scroll;">	
				<h1><fmt:message key="errorPage.heading"/></h1>
                <%@ include file="/common/messages.jsp" %>
                 <% if (exception != null) { %>
                    <pre><% exception.printStackTrace(new java.io.PrintWriter(out)); %></pre>
                 <% } else if ((Exception)request.getAttribute("javax.servlet.error.exception") != null) { %>
                    <pre><% ((Exception)request.getAttribute("javax.servlet.error.exception"))
                                           .printStackTrace(new java.io.PrintWriter(out)); %></pre>
                 <% } %>
			</div>
		</div>
	</div><!--





    <div id="page">
        <div id="content" class="clearfix">
            <div id="main">
            
                <h1><fmt:message key="errorPage.heading"/></h1>
                <%@ include file="/common/messages.jsp" %>
                 <% if (exception != null) { %>
                    <pre><% exception.printStackTrace(new java.io.PrintWriter(out)); %></pre>
                 <% } else if ((Exception)request.getAttribute("javax.servlet.error.exception") != null) { %>
                    <pre><% ((Exception)request.getAttribute("javax.servlet.error.exception"))
                                           .printStackTrace(new java.io.PrintWriter(out)); %></pre>
                 <% } %>
            </div>
        </div>
    </div>
--></body>
</html>
