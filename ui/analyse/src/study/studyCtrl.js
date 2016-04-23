var m = require('mithril');
var partial = require('chessground').util.partial;
var throttle = require('../util').throttle;

module.exports = {
  init: function(data, ctrl) {

    var send = ctrl.socket.send;
    var userId = ctrl.userId;

    var vm = {
      memberConfig: null // which user is being configured by us
    };

    function myMember() {
      return userId ? data.members[userId] : null;
    }

    function owner() {
      return data.members[data.ownerId];
    }

    function meOrOwner() {
      return myMember() || owner();
    }

    function canContribute() {
      return myMember() && myMember().role === 'w';
    }

    function orderedMembers() {
      return Object.keys(data.members).map(function(id) {
        return data.members[id];
      }).sort(function(a, b) {
        return a.addedAt > b.addedAt;
      });
    }

    function addChapterId(req) {
      req.chapterId = data.position.chapterId;
      return req;
    }

    function updateShapes() {
      var shapes = ctrl.vm.path === data.position.path ? data.shapes : [];
      ctrl.chessground.setShapes(shapes);
    }
    ctrl.userJump(data.position.path);
    updateShapes();

    function samePosition(p1, p2) {
      return p1.chapterId === p2.chapterId && p1.path === p2.path;
    }

    ctrl.chessground.set({
      drawable: {
        onChange: function(shapes) {
          if (canContribute()) send("shapes", shapes);
        }
      }
    });

    return {
      data: data,
      vm: vm,
      userId: userId,
      position: function() {
        return data.position;
      },
      setPath: throttle(300, false, function(path) {
        if (canContribute() && path !== data.position.path) {
          data.shapes = [];
          send("setPath", addChapterId({
            path: path
          }));
        }
      }),
      deleteVariation: function(path) {
        send("deleteVariation", addChapterId({
          path: path
        }));
      },
      promoteVariation: function(path) {
        send("promoteVariation", addChapterId({
          path: path
        }));
      },
      orderedMembers: orderedMembers,
      setRole: function(userId, role) {
        send("setRole", {
          userId: userId,
          role: role
        });
        setTimeout(function() {
          vm.memberConfig = null;
          m.redraw();
        }, 400);
      },
      invite: function(username) {
        send("invite", username);
      },
      kick: function(userId) {
        send("kick", userId);
        vm.memberConfig = null;
      },
      onShowGround: function() {
        updateShapes();
      },
      socketHandlers: {
        path: function(p) {
          data.position.path = p;
          data.shapes = [];
          ctrl.userJump(p);
          m.redraw();
        },
        addNode: function(d) {
          if (d.p.chapterId !== data.position.chapterId) return;
          ctrl.tree.addNode(d.n, d.p.path);
          data.position.path = d.p.path + d.n.id;
          ctrl.jump(data.position.path);
          m.redraw();
        },
        delNode: function(d) {
          if (d.p.chapterId !== data.position.chapterId) return;
          ctrl.tree.deleteNodeAt(d.p.path);
          ctrl.jump(ctrl.vm.path);
          m.redraw();
        },
        reload: function() {
          lichess.reload();
        },
        members: function(d) {
          data.members = d;
          updateShapes();
          m.redraw();
        },
        shapes: function(d) {
          data.shapes = d;
          updateShapes();
          m.redraw();
        }
      }
    };
  }
};