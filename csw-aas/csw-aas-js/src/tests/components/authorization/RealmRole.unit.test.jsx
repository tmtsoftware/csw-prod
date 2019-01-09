import React from 'react'
import Enzyme, { mount } from 'enzyme'
import Adapter from 'enzyme-adapter-react-16'

describe('<RealmRole />', () => {
  Enzyme.configure({ adapter: new Adapter() })

  beforeEach(() => {
    jest.resetModules()
  })

  it('should render children elements if authentication is true and with valid realm role', () => {
    const getRealmRoleWithMockContext = () => {
      const mockContext = {
        tmtAuth: {
          hasRealmRole: jest.fn().mockImplementation(() => { return true })
        },
        isAuthenticated: true,
        login: () => true,
        logout: () => true
      }
      jest.mock('../../../components/context/TMTAuthContext', () => {
        return ({
          Consumer: jest.fn().mockImplementation((props) => {
            return (
              props.children(mockContext)
            )
          })
        })
      }
      )
      return require('../../../components/authorization/RealmRole').default
    }

    const RealmRoleComponent = getRealmRoleWithMockContext()

    const props = {
      children: <div className='realm-role'>Authentication successful</div>,
      error: <div className='error'>Authentication unsuccessful</div>,
      realmRole: 'test-realm-role'
    }

    const wrapper = mount(<RealmRoleComponent {...props} />)

    expect(wrapper.find('div.realm-role').length).toBe(1)
    expect(wrapper.find('div.error').length).toBe(0)
  })

  it('should not render children elements if authentication is true but invalid realm role', () => {
    const getRealmRoleWithMockContext = () => {
      const mockContext = {
        tmtAuth: {
          hasRealmRole: jest.fn().mockImplementation(() => { return false })
        },
        isAuthenticated: true,
        login: () => true,
        logout: () => true
      }
      jest.mock('../../../components/context/TMTAuthContext', () => {
        return ({
          Consumer: jest.fn().mockImplementation((props) => {
            return (
              props.children(mockContext)
            )
          })
        })
      }
      )
      return require('../../../components/authorization/RealmRole').default
    }

    const RealmRoleComponent = getRealmRoleWithMockContext()

    const props = {
      children: <div className='realm-role'>Authentication successful</div>,
      error: <div className='error'>Authentication unsuccessful</div>,
      realmRole: 'invalid-realm-role'
    }

    const wrapper = mount(<RealmRoleComponent {...props} />)

    expect(wrapper.find('div.realm-role').length).toBe(0)
    expect(wrapper.find('div.error').length).toBe(1)
  })

  it('should not render children elements if authentication is false ', () => {
    const getRealmRoleWithMockContext = () => {
      const mockContext = {
        tmtAuth: {
          hasRealmRole: jest.fn().mockImplementation(() => { return true })
        },
        isAuthenticated: false,
        login: () => true,
        logout: () => true
      }
      jest.mock('../../../components/context/TMTAuthContext', () => {
        return ({
          Consumer: jest.fn().mockImplementation((props) => {
            return (
              props.children(mockContext)
            )
          })
        })
      }
      )
      return require('../../../components/authorization/RealmRole').default
    }

    const RealmRoleComponent = getRealmRoleWithMockContext()

    const props = {
      children: <div className='realm-role'>Authentication successful</div>,
      error: <div className='error'>Authentication unsuccessful</div>,
      realmRole: 'invalid-realm-role'
    }

    const wrapper = mount(<RealmRoleComponent {...props} />)

    expect(wrapper.find('div.realm-role').length).toBe(0)
    expect(wrapper.find('div.error').length).toBe(1)
  })
})