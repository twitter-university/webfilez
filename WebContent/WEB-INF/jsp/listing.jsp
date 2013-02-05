<%@page session="false" contentType="text/html; charset=ISO-8859-1"%>
<!DOCTYPE html>
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<link rel="stylesheet" href="/styles/style.css" type="text/css" />
<link rel="shortcut icon" href="/favicon.ico" type="image/x-icon" />
<link href="//ajax.googleapis.com/ajax/libs/jqueryui/1.9.2/themes/base/jquery-ui.css" rel="stylesheet" type="text/css"/>
<script type="text/javascript">
  var writeAllowed = ${writeAllowed? 'true' : 'false'};
</script>
<script type="text/javascript" src="//ajax.googleapis.com/ajax/libs/jquery/1.8.3/jquery.min.js"></script>
<script type="text/javascript" src="//ajax.googleapis.com/ajax/libs/jqueryui/1.9.2/jquery-ui.min.js"></script>  
<script type="text/javascript" src="/js/modernizr.js"></script>
<script type="text/javascript" src="/js/listing.js"></script>
<title>Listing...</title>
</head>
<body>
  <h1>Listing...</h1>
  <div id="status"><span></span></div>
  <form id="form" action="" method="post">
    <div id="toolbar">
      <button id="refresh_button" type="button" class="button">Refresh</button>
      <button id="newdir_button" type="button" class="button write-operation">New Directory</button>
      <button id="newfile_button" type="button" class="button write-operation">New File</button>
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
          <td>Drag and drop files here to upload them to this folder</td>
          <td></td>
          <td></td>
        </tr>
      </tfoot>
    </table>
  </form>
  <div id="readme"></div>
</body>
</html>