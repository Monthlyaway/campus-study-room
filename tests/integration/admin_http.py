#!/usr/bin/env python3
"""Real D API regression, including shared-session revocation in both services."""
import os
import unittest
import urllib.parse
import uuid
from repair_http import Client

class AdministrationHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.password=os.environ.get('STUDYROOM_TEST_PASSWORD')
        if not cls.password:
            raise RuntimeError('STUDYROOM_TEST_PASSWORD must be supplied; integration tests are not silently skipped')
        cls.admin=Client('admin',cls.password)
        cls.student_role=next(r['id'] for r in cls.admin.get('/api/v1/roles')['items'] if r['code']=='STUDENT')

    def user(self):
        name='admintest-'+uuid.uuid4().hex[:12]
        status,body=self.admin.call('POST','/api/v1/users',{'username':name,'displayName':'管理 HTTP 验收 '+name,'password':self.password,'email':name+'@example.invalid','schoolNo':name,'departmentId':self.admin.me['departmentId']})
        self.assertEqual(201,status,body)
        self.assertNotIn('passwordHash',body['data'])
        return name,body['data']

    def test_search_self_binding_and_cross_service_revocation(self):
        name,user=self.user()
        found=self.admin.get('/api/v1/users?q='+urllib.parse.quote(name.upper())+'&page=1&size=1')
        self.assertEqual(1,found['total']);self.assertEqual(user['id'],found['items'][0]['id'])
        self.assertNotIn('passwordHash',found['items'][0])
        old=Client(name,self.password)
        self.assertIn('STUDYROOMSESSION',[c.name for c in old.cookies])
        status,body=self.admin.call('PUT','/api/v1/users/'+user['id']+'/role-bindings',{'expectedVersion':user['version'],'bindings':[{'roleId':self.student_role,'scopes':[{'scopeType':'SELF'}]}]})
        self.assertEqual(200,status,body)
        self.assertEqual(401,old.call('GET','/api/v1/auth/me')[0])
        current=Client(name,self.password)
        self.assertTrue(any(s['scopeType']=='SELF' for s in current.me['scopes']))
        self.assertIn('reservationMaxHours',current.me['bookingLimits'])
        self.assertEqual(403,current.call('GET','/api/v1/users')[0])
        self.assertEqual(200,current.call('GET','/api/v1/repairs')[0])
        status,disabled=self.admin.call('PATCH','/api/v1/users/'+user['id'],{'expectedVersion':body['data']['version'],'enabled':False})
        self.assertEqual(200,status,disabled)
        self.assertEqual(401,current.call('GET','/api/v1/repairs')[0])
        self.assertEqual(401,current.call('GET','/api/v1/auth/me')[0])

    def test_authorization_room_directory_requires_role_management(self):
        directory=self.admin.get('/api/v1/role-scope-rooms?page=1&size=100')
        self.assertGreater(len(directory['items']),0)
        self.assertEqual({'id','name','code','enabled','buildingName','departmentName'},set(directory['items'][0]))
        selected=directory['items'][0]
        search=self.admin.get('/api/v1/role-scope-rooms?q='+urllib.parse.quote(selected['code']))
        self.assertIn(selected['id'],[r['id'] for r in search['items']])
        self.assertEqual(400,self.admin.call('GET','/api/v1/role-scope-rooms?page=0')[0])
        student=Client('student',self.password)
        self.assertEqual(403,student.call('GET','/api/v1/role-scope-rooms')[0])
        self.assertEqual(403,student.call('GET','/api/v1/notification-scope-rooms')[0])
        managed=self.admin.get('/api/v1/notification-scope-rooms?page=1&size=100')
        self.assertEqual({'id','name','code','enabled','buildingName','departmentName'},set(managed['items'][0]))
        room_admin=Client('room-admin',self.password)
        self.assertEqual(403,room_admin.call('GET','/api/v1/role-scope-rooms')[0])

    def test_invalid_scope_rolls_back_and_stale_version_conflicts(self):
        name,user=self.user()
        status,body=self.admin.call('PUT','/api/v1/users/'+user['id']+'/role-bindings',{'expectedVersion':user['version'],'bindings':[{'roleId':self.student_role,'scopes':[{'scopeType':'SELF','departmentId':self.admin.me['departmentId']}]}]})
        self.assertEqual(422,status,body);self.assertEqual('INVALID_SCOPE',body['error']['code'])
        unchanged=self.admin.get('/api/v1/users?q='+name)['items'][0]
        self.assertEqual(user['version'],unchanged['version']);self.assertEqual([],unchanged['roleBindings'])
        status,body=self.admin.call('PATCH','/api/v1/users/'+user['id'],{'expectedVersion':user['version'],'displayName':'修改后的管理验收用户'})
        self.assertEqual(200,status,body)
        status,stale=self.admin.call('PATCH','/api/v1/users/'+user['id'],{'expectedVersion':user['version'],'enabled':False})
        self.assertEqual(409,status,stale);self.assertEqual('VERSION_CONFLICT',stale['error']['code'])

if __name__=='__main__':unittest.main(verbosity=2)
