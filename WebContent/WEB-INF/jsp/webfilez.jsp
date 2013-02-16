<%@ page session="false" contentType="text/html; charset=UTF-8"%>
<%@ taglib prefix="w" uri="/WEB-INF/webfilez.tld" %>
<!DOCTYPE html>
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<link rel="shortcut icon" href="/favicon.ico" type="image/x-icon" />
<link href="//ajax.googleapis.com/ajax/libs/jqueryui/1.9.2/themes/base/jquery-ui.css" rel="stylesheet" type="text/css"/>
<link rel="stylesheet" href="/styles/style.css?v=<w:lastModified filePath='/styles/style.css'/>" type="text/css" />
<script type="text/javascript">
  var writeAllowed = ${writeAllowed? 'true' : 'false'};
</script>
<script type="text/javascript" src="//ajax.googleapis.com/ajax/libs/jquery/1.8.3/jquery.min.js"></script>
<script type="text/javascript" src="//ajax.googleapis.com/ajax/libs/jqueryui/1.9.2/jquery-ui.min.js"></script>  
<script type="text/javascript" src="/js/modernizr.js"></script>
<script type="text/javascript" src="/js/webfilez.js?v=<w:lastModified filePath='/js/webfilez.js'/>"></script>

<title>Listing...</title>
</head>
<body>
  <h1>Listing...</h1>
  <div id="description">${description}</div>
  <div id="status"><span></span></div>
  <form id="form" action="" method="post">
    <div id="toolbar">
      <button id="refresh_button" type="button" class="button">Refresh</button>
      <button id="newdir_button" type="button" class="button write-operation">New Directory</button>
      <button id="newfile_button" type="button" class="button write-operation">New File</button>
      <button id="upload_button" type="button" class="button write-operation">Upload</button>
      <button id="delete_button" type="button" class="action-button write-operation">Delete</button>
      <button id="zip_button" type="button" class="action-button write-operation">Zip</button>
      <button id="download_zip_button" type="button" class="action-button">Download as Zip</button>
      <button id="copy_button" type="button" class="action-button write-operation">Copy</button>
      <button id="cut_button" type="button" class="action-button write-operation">Cut</button>
      <button id="paste_button" type="button" class="button write-operation" disabled="disabled">Paste</button>
      <button id="clear_button" type="button" class="button" disabled="disabled">Clear</button>
    </div>
    <table id="listing">
      <thead>
        <tr>
          <th class="file-select"><input type="checkbox" id="toggle" name="toggle" /></th>
          <th class="file-name-header">Name</th>
          <th class="file-size-header">Size</th>
          <th class="file-last-modified-date-header">Last Modified</th>
        </tr>
      </thead>
      <tbody>
      </tbody>
      <tfoot class="write-operation">
        <tr>
          <td></td>
          <td>Drag and drop files here to upload them to this directory</td>
          <td></td>
          <td></td>
        </tr>
      </tfoot>
    </table>
  </form>
  <div id="readme"></div>
  <div id="upload-dialog" title="File Upload" style="display: none">
    <table>
      <thead>
        <tr>
          <th class="file-name-header">Name</th>
          <th class="file-size-header">Size</th>
          <th class="file-last-modified-date-header">Last Modified</th>
        </tr>
      </thead>
      <tbody>
      </tbody>
      <tfoot class="write-operation">
        <tr>
          <td>
            Drag and drop files here to upload them to this directory
            or
            <input type="file" multiple="multiple"/>
          </td>
          <td></td>
          <td></td>
        </tr>
      </tfoot>
    </table>
  </div>
</body>
</html>