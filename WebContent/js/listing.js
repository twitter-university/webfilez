function toDateAndTime(input) {
  var date = input instanceof Date ? input : new Date(input);
  return date.toLocaleDateString() + " " + date.toLocaleTimeString();
}

function addCommas(nStr) {
  if (nStr) {
    nStr += '';
    x = nStr.split('.');
    x1 = x[0];
    x2 = x.length > 1 ? '.' + x[1] : '';
    var rgx = /(\d+)(\d{3})/;
    while (rgx.test(x1)) {
      x1 = x1.replace(rgx, '$1' + ',' + '$2');
    }
    return x1 + x2;
  } else {
    return 0;
  }
}

function getFilenameFromRow(tr) {
  return tr.find("input[type='checkbox'][name='file']").attr('value');
}

function setupRename() {
  var tr = $(this).closest('tr');
  var filename = getFilenameFromRow(tr);
  var td = tr.find('td.file-name');
  var a = td.find('a');
  var buttons = td.find('button');
  var newNameInput = $("<input>").attr('type', 'text').attr('name', 'newName').attr('value',
      filename);
  newNameInput.blur(function() {
    newNameInput.remove();
    a.show();
    buttons.show();
  });
  newNameInput.bind('keypress', function(e) {
    if (e.keyCode == 13) {
      $.ajax({
        url : toUri(filename),
        type : "POST",
        data : "_action=rename&" + newNameInput.serialize(),
        context : filename
      }).done(function() {
        var newName = newNameInput.attr('value');
        setStatus("Renamed '" + filename + "' to '" + newName + "'");
        tr.find("td.file-select input").attr('value', newName);
        if (tr.hasClass('directory')) {
          newName += "/";
        }
        a.attr('href', newName);
        a.html(newName);
        newNameInput.remove();
        a.show();
        buttons.show();
      }).fail(handleError);
      return false;
    }
  });
  newNameInput.insertAfter(a);
  a.hide();
  buttons.hide();
  newNameInput.focus();
}

function setupNewResource(file) {
  var tr = fileToRow(file);
  var td = tr.find('td.file-name');
  td.find('button').remove();
  var a = td.find('a');
  var newNameInput = $("<input>").attr('type', 'text').attr('value', file.name);
  newNameInput.blur(function() {
    tr.remove();
  });
  newNameInput.bind('keypress', function(e) {
    if (e.keyCode == 13) {
      var filename = $(this).attr('value');
      $.ajax({
        url : toUri(filename),
        type : 'PUT',
        contentType : file.type,
        context : filename
      }).done(function(file) {
        tr.replaceWith(fileToRow(file));
        setStatus("Created '" + file.name + "'");
      }).fail(handleError);
      return false;
    }
  });
  newNameInput.insertAfter(a);
  a.hide();
  $("table#listing tbody").append(tr);
  newNameInput.focus();
  newNameInput.select();
}

function unzip() {
  var filename = getFilenameFromRow($(this).closest('tr'));
  $.ajax({
    url : toUri(filename),
    type : "POST",
    data : "_action=unzip",
    dataType : 'json',
    beforeSend : function() {
      setStatus("Unzipping '" + filename + "' ...", true);
    },
    context : filename
  }).done(function(files) {
    for ( var i = 0; i < files.length; i++) {
      removeFileRow(files[i].name);
      var tr = fileToRow(files[i]);
      $("#listing").find("tbody").append(tr);
      tr.effect("highlight", {}, 500);
    }
    setStatus("Unzipped '" + filename + "'");
  }).fail(handleError);
}

function downloadAsZip() {
  var filename = getFilenameFromRow($(this).closest('tr'));
  window.location.href = window.location.pathname + '?_action=zip_download&file='
      + encodeURI(filename);
}

function saveFile(filename, lastModified, data, tr, onSaveFn) {
  log("Saving " + filename + " last modified " + lastModified);
  $.ajax({
    url : toUri(filename),
    type : 'PUT',
    headers : {
      'If-Unmodified-Since' : lastModified
    },
    data : data,
    contentType : "text/plain",
    dataType : 'json'
  }).done(function(file) {
    log("Saved " + file.name + " last modified " + file.lastModified);
    tr.replaceWith(fileToRow(file));
    if (onSaveFn) {
      onSaveFn(file);
    }
  }).fail(handleError);
  return false;
}

function edit() {
  var tr = $(this).closest('tr');
  var filename = getFilenameFromRow(tr);
  log("Editing " + filename);
  var content = $("<textarea>").attr('name', 'content');
  var lastModified = null;
  var editDialog = $("<div>").attr('id', 'edit').attr("title", filename).html("<p>Loading...</p>");
  editDialog.dialog({
    width : 640,
    height : 480,
    modal : true,
    buttons : {
      OK : function() {
        saveFile(filename, lastModified, content.attr('value'), tr, function(file) {
          setStatus("Saved '" + filename + "'");
          editDialog.dialog("destroy");
        });
      },
      Apply : function() {
        saveFile(filename, lastModified, content.attr('value'), tr, function(file) {
          lastModified = new Date(file.lastModified).toGMTString();
          editDialog.dialog("option", "title", filename + " (saved)").effect("highlight", {}, 500);
          setTimeout(function() {
            $("#edit").dialog("option", "title", filename);
          }, 1000);
        });
      },
      Cancel : function() {
        $(this).dialog("destroy");
      }
    }
  });
  $.ajax({
    url : toUri(filename),
    type : "GET",
    context : editDialog
  }).done(function(data, textStatus, jqXHR) {
    editDialog.empty();
    editDialog.append(content.text(data));
    lastModified = jqXHR.getResponseHeader('Last-Modified');
  }).fail(handleError);
}

function fileToRow(file) {
  var isDir = file.type === 'x-directory/normal';
  var name = isDir ? file.name + '/' : file.name;
  var tr = $('<tr>').addClass(isDir ? 'directory' : 'file');
  var td = $('<td>').addClass('file-select').append(
      $('<input>').attr('type', 'checkbox').attr('name', 'file').attr('value', file.name));
  tr.append(td);
  td = $('<td>').addClass('file-name').append($('<a>').attr('href', toUri(name)).html(name));
  if (writeAllowed) {
    td.append($('<button>').addClass('inline-button').addClass('delete-button').addClass(
        'write-operation').attr('type', 'button').click(deleteFile).html('Delete'));
    td.append($('<button>').addClass('inline-button').addClass('rename-button').addClass(
        'write-operation').attr('type', 'button').click(setupRename).html('Rename'));
    if (file.type === 'application/zip') {
      td.append($('<button>').addClass('inline-button').addClass('unzip-button').addClass(
          'write-operation').attr('type', 'button').click(unzip).html('Unzip'));
    }
    if ((file.type.match(/^text/i) || file.type.match(/xml/i)) && file.size <= 3 * 1024 * 1024) {
      td.append($('<button>').addClass('inline-button').addClass('edit-button').addClass(
          'write-operation').attr('type', 'button').click(edit).html('Edit'));
    }
  }
  if (!file.name
      .match(/\.(zip|jar|war|7z|rar|gz|bz2|Z|gif|jpe?g|png|bmp|wave?|mp3|aac|mov|mp4a?|mpeg|m4v|wmv|avi)$/i)) {
    td.append($('<button>').addClass('inline-button').addClass('download-as-zip-button').attr(
        'type', 'button').click(downloadAsZip).html('Download as ZIP'));
  }
  tr.append(td);
  td = $('<td>').addClass('file-size').html(addCommas(file.size));
  tr.append(td);
  td = $('<td>').addClass('file-last-modified-date').html(toDateAndTime(file.lastModified));
  tr.append(td);
  return tr;
}

function toUri(filename) {
  return window.location.pathname + encodeURI(filename);
}

function list(url) {
  log("Listing " + url);
  $.ajax({
    url : url,
    type : "GET",
    dataType : 'json',
    cache : false,
    context : $(this)
  }).done(
      function(response) {
        document.title = response.uri;
        baseUrl = response.uri;
        $("h1").html(decodeURI(response.uri));
        var tbody = $("#listing").find("tbody");
        tbody.empty();
        if (response.parent) {
          var tr = $('<tr>').addClass('directory');
          tr.append($('<td>').addClass('file-select').html(''));
          tr.append($('<td>').addClass('file-name').append(
              $('<a>').attr('href', response.parent).html('..')));
          tr.append($('<td>').addClass('file-size').html(''));
          tr.append($('<td>').addClass('file-last-modified-date').html(''));
          tbody.append(tr);
        }
        for ( var i = 0; i < response.files.length; i++) {
          tbody.append(fileToRow(response.files[i]));
        }
        var readme = $("#readme");
        if (response.readme) {
          readme.html(response.readme);
        } else {
          readme.empty();
        }
      }).fail(handleError);
}

function handleError(jqXHR, textStatus) {
  log("Error [" + jqXHR.status + "]: " + textStatus);
  switch (jqXHR.status) {
  case 400:
    setStatus("Operation refused.");
    break;
  case 401:
    if (confirm("It appears that your session has timed-out. We need to reload this page.")) {
      window.location.reload();
    } else {
      log("Reload refused");
    }
    break;
  case 403:
    setStatus("Operation not allowed.");
    break;
  case 404:
    setStatus("File/directory no longer exists. Refreshing the list.");
    list(window.location.pathname);
    break;
  case 412:
    setStatus("Concurrent modification detetected. Reload and try again.");
    break;
  case 500:
    if (confirm("Operation failed. We recommend that you reload this page and try again.")) {
      window.location.reload();
    } else {
      log("Reload refused");
      setStatus("Operation failed");
    }
    break;
  default:
    setStatus("Operation failed for an unknown reason. You may reload this page and try again.");
  }
}

function getRowForFilename(filename) {
  var tr = $(
      "#listing tbody td.file-select input[type='checkbox'][name='file'][value='" + filename + "']")
      .closest('tr');
  return tr.length == 1 ? tr : null;
}

function removeFileRow(filename) {
  var tr = getRowForFilename(filename);
  if (tr) {
    tr.fadeOut(400, function() {
      $(this).remove();
    });
  }
}

function deleteNextFile(filenames) {
  var filename = filenames.pop();
  if (filename) {
    $.ajax({
      url : toUri(filename),
      type : "DELETE",
      context : filename,
      beforeSend : function() {
        setStatus("Deleting '" + filename + "' ...", true);
      }
    }).done(function() {
      removeFileRow(filename);
      setStatus("Deleted '" + filename + "'");
      deleteNextFile(filenames);
    }).fail(handleError);
  }
}

function handleDelete() {
  var filenames = getSelectedFileNames();
  if (confirm("Please confirm that you wish to delete the following resources:\n\n"
      + filenames.join("\n"))) {
    setEnabledStatusOnActionButtons(false);
    deleteNextFile(filenames.reverse());
  }
  return false;
}

function deleteFile() {
  var filename = getFilenameFromRow($(this).closest('tr'));
  if (confirm("Please confirm that you wish to delete '" + filename + "'?")) {
    deleteNextFile([ filename ]);
  }
  return false;
}

function handleZip() {
  setStatus("Creating a ZIP archive ...", true);
  $.ajax({
    url : window.location.pathname,
    type : "POST",
    data : "_action=zip&" + $("#form").serialize(),
    dataType : 'json'
  }).done(function(file) {
    setEnabledStatusOnActionButtons(false);
    clearFilenameSelection();
    setStatus("Created a ZIP archive '" + file.name + "'");
    var tr = fileToRow(file);
    $("#listing").find("tbody").append(tr);
    tr.effect("highlight", {}, 500);
  }).fail(handleError);
  return false;
}

function handleDownloadZip() {
  var url = window.location.pathname + '?_action=zip_download&'
      + $("#form input[type='checkbox'][name='file']").serialize();
  setEnabledStatusOnActionButtons(false);
  clearFilenameSelection();
  window.location.href = url;
  return false;
}

function getSelectedFileNames() {
  return $('td.file-select input:checkbox:checked').map(function() {
    return this.value;
  }).get();
}

function setEnabledStatus(element, enabled) {
  if (enabled) {
    element.removeAttr("disabled");
  } else {
    element.attr("disabled", "disabled");
  }
}

function setEnabledStatusOnActionButtons(enabled) {
  setEnabledStatus($("button.action-button"), enabled);
}

function setEnabledStatusOnPasteButton(enabled) {
  setEnabledStatus($("#paste_button"), enabled);
}

function setEnabledStatusOnClearButton(enabled) {
  setEnabledStatus($("#clear_button"), enabled);
}

function clearFilenameSelection() {
  $(".file-select input[type='checkbox']").attr('checked', false);
}

function log(s) {
  if (console && console.log) {
    console.log(s);
  }
}

function noop(event) {
  event.stopPropagation();
  event.preventDefault();
}

function setStatus(status, ongoing) {
  var s = $("#status span").html(status).effect("highlight", {}, 500);
  if (ongoing) {
    s.addClass("ongoing");
  } else {
    s.removeClass("ongoing");
  }
}

function uploadNextFile(xhrs) {
  var xhr = xhrs.pop();
  if (xhr) {
    log("Executing upload for " + xhr.mstate.uri);
    xhr.open("PUT", xhr.mstate.uri, true);
    xhr.send(xhr.mstate.formData);
  } else {
    log("No more xhrs to execute");
  }
}

function onFilesToUpload(event) {
  var files = event.dataTransfer.files;
  var tbody = $("#listing tbody");
  var xhrs = new Array();
  for ( var i = 0; i < files.length; i++) {
    var xhr = new XMLHttpRequest();
    var file = files[i];
    var newTr = fileToRow({
      name : file.name,
      size : file.size,
      type : file.type,
      lastModified : file.lastModifiedDate
    });
    var tr = getRowForFilename(file.name);
    if (tr) {
      tr.replaceWith(newTr);
      tr = newTr;
    } else {
      tr = newTr;
      tbody.append(tr);
    }
    var td = tr.find('td.file-name');
    td.find('button').remove();
    var status = $('<span>').addClass('status').addClass('ongoing').html('Pending Upload');
    td.append(status);
    var progressBar = $('<div>').addClass('progress-bar').width('35%').height('1em').progressbar();
    td.append(progressBar);
    status.html("Uploading ...");
    var formData = new FormData();
    formData.append("file", file);

    xhr.mstate = {
      uri : toUri(file.name),
      file : file,
      tr : tr,
      td : td,
      status : status,
      progressBar : progressBar,
      formData : formData
    };

    xhr.upload.addEventListener("progress", function(event) {
      if (event.lengthComputable) {
        var progress = Math.round(event.loaded / event.total * 100);
        this.mstate.progressBar.progressbar("value", progress);
      }
    }, false);
    xhr.upload.mstate = xhr.mstate;
    xhr.addEventListener("load", function(event) {
      var f = JSON.parse(event.target.responseText);
      this.mstate.tr.replaceWith(fileToRow(f));
      setStatus("Uploaded '" + f.name + "'");
      uploadNextFile(xhrs);
    }, false);
    xhr.addEventListener("error", function(event) {
      setStatus("There was an error attempting to upload file '" + file.name + "'. Aborting.");
      this.mstate.tr.remove();
    }, false);
    xhr.addEventListener("abort", function(event) {
      setStatus("The upload of '" + file.name
          + "' has been canceled or the browser dropped the connection. Aborting.");
      this.mstate.tr.remove();
    }, false);
    xhrs.push(xhr);
  }
  uploadNextFile(xhrs.reverse());
}

function setupDragAndDrop(container, dropFunction) {
  container.addEventListener('dragstart', noop, false);
  container.addEventListener('dragenter', noop, false);
  container.addEventListener('dragover', function(event) {
    noop(event);
    if (!container.className) {
      container.className = 'dragover';
    }
  }, false);
  container.addEventListener('dragleave', function(event) {
    noop(event);
    container.className = null;
  }, false);
  container.addEventListener('drop', function(event) {
    noop(event);
    container.className = null;
    dropFunction(event);
  }, false);
}

function selectFilesFor(action) {
  log("Selecting files for " + action);
  var filenames = getSelectedFileNames();
  if (filenames.length > 0) {
    setEnabledStatusOnActionButtons(false);
    setEnabledStatusOnPasteButton(true);
    setEnabledStatus($("#clear_button"), true);
    var paths = new Array();
    for ( var i = 0; i < filenames.length; i++) {
      paths.push(toUri(filenames[i]));
    }
    localStorage.setItem("source", JSON.stringify({
      action : action,
      paths : paths
    }));
    setStatus("Selected " + filenames.length + " "
        + (filenames.length === 1 ? "file/directory" : "files/directories")
        + ". Go to folder of your choice and click on 'Paste' to complete the " + action
        + " operation.");
    clearFilenameSelection();
    return false;
  } else {
    log("No files selected for '" + action + "'");
  }
}

function clearSelectedFiles() {
  localStorage.removeItem("source");
}

function hasSelectedFiles() {
  return localStorage.hasOwnProperty("source");
}

function startsWith(s1, s2) {
  return s1.lastIndexOf(s2, 0) === 0;
}

function handlePaste() {
  var source = localStorage.getItem("source");
  if (source) {
    source = JSON.parse(source);
    setEnabledStatusOnPasteButton(false);
    handleSinglePaste(source.action, source.paths.reverse());
    clearSelectedFiles();
  }
}

function handleSinglePaste(action, paths) {
  var path = paths.pop();
  if (path) {
    log("Executing " + action + " on " + path);
    setStatus((action === 'copy' ? "Copying" : "Moving") + " '" + file.name + "' ...", true);
    $.ajax({
      url : window.location.pathname,
      type : "POST",
      data : "_action=" + action + "&source=" + path,
      dataType : 'json'
    }).done(function(file) {
      log("Executed " + action + " on " + path);
      setStatus((action === 'copy' ? "Copied" : "Moved") + " '" + file.name + "'");
      var tr = fileToRow(file);
      $("#listing").find("tbody").append(tr);
      tr.effect("highlight", {}, 500);
      handleSinglePaste(action, paths);
    }).fail(handleError);
  } else {
    log("Done executing " + action);
  }
}

function handleRefresh() {
  log("Refreshing");
  list(window.location.pathname);
}

function handleNewDir() {
  log("Setting up for new directory");
  setupNewResource({
    type : 'x-directory/normal',
    name : 'New Folder',
  });
}

function handleNewFile() {
  setupNewResource({
    type : 'text/plain',
    name : 'New File.txt',
  });
}

function handleCopy() {
  selectFilesFor('copy');
}

function handleCut() {
  selectFilesFor('move');
}

function handleClear() {
  clearFilenameSelection();
  clearSelectedFiles();
  setEnabledStatus($(this), false);
}

var doPop = false;

$(document).ready(function() {
  $(document).on("click", ".file-select input[type='checkbox']", function() {
    setEnabledStatusOnActionButtons(this.checked);
  });

  $("th.file-select input[type='checkbox']").click(function() {
    $("td.file-select input[type='checkbox']").attr('checked', this.checked);
  });

  $("#refresh_button").click(handleRefresh);
  $("#download_zip_button").click(handleDownloadZip);
  if (writeAllowed) {
    $("#delete_button").click(handleDelete);
    $("#zip_button").click(handleZip);
    $("#newdir_button").click(handleNewDir);
    $("#newfile_button").click(handleNewFile);
    $("#copy_button").click(handleCopy);
    $("#cut_button").click(handleCut);
    $("#paste_button").click(handlePaste);
    $("#clear_button").click(handleClear);
    setEnabledStatusOnPasteButton(hasSelectedFiles());
    setupDragAndDrop(document.getElementById("listing"), onFilesToUpload);
  } else {
    $(".write-operation").remove();
  }

  setEnabledStatusOnActionButtons(false);
  setEnabledStatusOnClearButton(hasSelectedFiles());

  if (Modernizr.touch) {
    $("html").addClass("touchDevice");
  }

  if (Modernizr.history) {
    $(document).on('click', 'table#listing tr.directory td.file-name a', function(event) {
      event.stopPropagation();
      var uri = $(this).attr('href');
      history.pushState(null, null, uri);
      log("Navigating to " + uri);
      doPop = true;
      list(uri);
      return false;
    });

    window.addEventListener("popstate", function(event) {
      if (doPop) {
        log("Navigating back");
        list(window.location.pathname);
        event.stopPropagation();
      } else {
        log("Ignoring invalid pop");
      }
    });
  }

  $(document).on("contextmenu", "table#listing tr.directory td.file-name", function(e) {
    log('Context Menu event has fired!');
    return false;
  });

  log("Listing for the first time");
  list(window.location.pathname);
});
