import { post, postWithLoadTip } from '@/utils/request'

export default {
  login: query => postWithLoadTip(`/api/student/login`, query),
  logout: query => post(`/api/student/logout`, query)
}
